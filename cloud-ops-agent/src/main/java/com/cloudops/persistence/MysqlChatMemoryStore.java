package com.cloudops.persistence;

import com.cloudops.entity.ChatMessageRecord;
import com.cloudops.mapper.ChatMessageMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dev.langchain4j.data.message.ChatMessageType.AI;
import static dev.langchain4j.data.message.ChatMessageType.USER;

/**
 * MySQL 对话记忆存储
 * <p>
 * ChatMemoryStore 接口方法被 LangChain4j 框架调用：
 *   - getMessages：每轮对话组装请求前读取历史（注意：单轮内每次 add 后也会重新调用，
 *     见 MessageWindowChatMemory.messages() 的字节码——它每次都回调 store.getMessages()）
 *   - updateMessages：每次 add 消息后写入
 * <p>
 * ★ 两层缓存问题的设计（重要）：
 * <p>
 * 1) 同轮「写后读」可见性（原有 bug）：
 *    LangChain4j 1.0.0-beta3 的 MessageWindowChatMemory.add() 内部先 messages()（即
 *    store.getMessages()）再 store.updateMessages()，两者同一请求。原实现 getMessages 直接
 *    查 MySQL，updateMessages 的 INSERT 在同一事务下尚未对后续 SELECT 可见 → getMessages 返回
 *    空列表 → 框架抛 "messages cannot be null or empty"。
 *    修复：updateMessages 同步写进程内缓存，getMessages 优先读缓存（带同轮桥接窗口），
 *          解决事务可见性 bug。
 * <p>
 * 2) 跨轮工具结果复用（本次核心修复）：
 *    getMessages 每次都返回「持久化/缓存里的完整历史」，其中包含上一轮工具执行的返回结果
 *    （如「2025 账单为空」）。当新一轮用不同查询条件提问时，大模型看到历史里已有工具结果，
 *    会直接复用而不重新调用工具 → 出现「不同查询条件拿到相同（错误）结果」的缓存不一致。
 * <p>
 *    ★ 修复策略：在「新一轮用户提问」的边界上剥离历史中的 ToolExecutionResultMessage，
 *      仅保留 user/ai/system 文本对话。
 *        - 判断边界：updateMessages 时若最后一条消息是 UserMessage，说明这是新一轮起点；
 *        - 此时把历史里的工具执行结果清空（只留文本），再落库 + 写缓存；
 *        - 单轮内的 ReAct 工具链不受影响：工具结果正常 add → updateMessages（最后一条不是
 *          UserMessage，不剥离）→ 下一轮 getMessages 仍读得到，推理链完整。
 *    效果：跨轮不再复用旧工具结果，大模型面对新查询条件必然重新调用工具查库；
 *          单轮多步工具推理照常工作。
 * <p>
 * 3) 缓存与 DB 一致性：
 *    缓存与 DB 始终写入同一份（经剥离处理后的）消息，二者一致；缓存未命中回源 DB。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_MESSAGES = 20;

    /**
     * 同轮「写后读」桥接窗口（毫秒）。
     * 单次对话内 add→messages（含 ReAct 多轮工具循环）通常在数百毫秒~数秒内完成，
     * 取 5s 留足余量；跨轮用户请求间隔通常远超窗口，自然回源 DB（二者内容一致，无副作用）。
     * 真正的跨轮一致性由「边界剥离工具结果」保证，与窗口大小无关。
     */
    private static final long SAME_TURN_BRIDGE_MS = 5_000L;

    private final ChatMessageMapper mapper;

    /**
     * 进程内缓存：sessionId -> (消息窗口, 写入时间戳)
     * 用于在「add 写库 → messages 读库」的同一请求内桥接事务可见性；
     * 缓存内容经过边界剥离处理，与 DB 保持一致。
     */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        CacheEntry entry = cache.get(sessionId);

        // 1. 命中且仍在同轮桥接窗口内 → 直接返回缓存（修复原 messages() 读不到刚插入行的问题）
        if (entry != null && (System.currentTimeMillis() - entry.writeTime) < SAME_TURN_BRIDGE_MS) {
            return new ArrayList<>(entry.messages);
        }

        // 2. 未命中或已超时 → 回源 MySQL（内容已含跨轮剥离后的文本历史），回填缓存
        List<ChatMessage> fromDb = loadFromDb(sessionId);
        cache.put(sessionId, new CacheEntry(fromDb, System.currentTimeMillis()));
        return fromDb;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();

        // 判断是否为「新一轮用户提问」起点：最后一条是 UserMessage 即新一轮边界
        boolean isNewTurnBoundary = !messages.isEmpty()
                && messages.get(messages.size() - 1).type() == USER;

        // 新一轮起点 → 剥离历史中的工具执行结果，仅保留 user/ai/system 文本对话，
        // 强制大模型在本轮用新条件重新调用工具查库，避免复用上一轮的错误/空结果。
        // 单轮内（最后一条非 UserMessage）则原样保留，保证 ReAct 工具链路完整。
        List<ChatMessage> toPersist = isNewTurnBoundary
                ? stripToolResults(messages)
                : new ArrayList<>(messages);

        // 1. 同步写入缓存并打时间戳（保证同轮 messages() 立即读到本轮消息）
        cache.put(sessionId, new CacheEntry(new ArrayList<>(toPersist), System.currentTimeMillis()));

        // 2. 落库 MySQL（失败不影响本次对话，仅丢失持久化）
        try {
            persistToDb(sessionId, toPersist);
        } catch (Exception e) {
            log.error("[ChatMemory] MySQL 持久化失败（不影响本次对话）, sessionId={}", sessionId, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        cache.remove(sessionId);
        try {
            mapper.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.error("[ChatMemory] MySQL 删除失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 成对剥离工具调用 + 工具执行结果。
     * <p>
     * 原因：OpenAI / DeepSeek API 强制要求「带 tool_calls 的 AssistantMessage 必须紧跟
     * ToolExecutionResultMessage」，否则报 invalid_request_error。
     * 因此不能只删结果而保留调用——必须成对移除（或整体保留）。
     * <p>
     * 策略：从历史中移除每个「AiMessage(tool_calls) + ToolExecutionResultMessage」对，
     * 仅保留纯文本对话（user / ai-text / system），使跨轮不再携带上一轮的工具返回值，
     * 避免大模型复用旧条件下的空/过期结果。
     */
    private List<ChatMessage> stripToolResults(List<ChatMessage> messages) {
        List<ChatMessage> stripped = new ArrayList<>(messages.size());
        int i = 0;
        int pairsRemoved = 0;

        while (i < messages.size()) {
            ChatMessage msg = messages.get(i);

            // 遇到工具执行结果 → 向前找到它响应的 AI(tool_calls)，整对跳过
            if (msg.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
                // 回退到stripped中最后一个 AI 消息（就是发起这次调用的那条）
                int aiIdx = findLastAiWithToolCalls(stripped);
                if (aiIdx >= 0) {
                    stripped.remove(aiIdx);
                }
                pairsRemoved++;
                i++;
                continue;
            }

            stripped.add(msg);
            i++;
        }

        if (pairsRemoved > 0) {
            log.debug("[ChatMemory] 新一轮起点，已剥离 {} 组跨轮工具调用+结果对（避免复用旧结果）", pairsRemoved);
        }
        return stripped;
    }

    /**
     * 从列表末尾向前查找最近一条带有 tool_calls 的 AiMessage 的索引。
     */
    private static int findLastAiWithToolCalls(List<ChatMessage> messages) {
        for (int j = messages.size() - 1; j >= 0; j--) {
            ChatMessage m = messages.get(j);
            if (m.type() == AI) {
                // AiMessage 可能有 tool_calls；LangChain4j 中 hasToolCalls() 判断
                dev.langchain4j.data.message.AiMessage ai = (dev.langchain4j.data.message.AiMessage) m;
                if (ai.hasToolExecutionRequests()) {
                    return j;
                }
            }
        }
        return -1;
    }

    /**
     * 缓存项：消息窗口 + 写入时间戳
     * 时间戳用于区分「同轮写后读（应读缓存）」与「跨轮请求（应回源 DB）」。
     */
    private static final class CacheEntry {
        final List<ChatMessage> messages;
        final long writeTime;

        CacheEntry(List<ChatMessage> messages, long writeTime) {
            this.messages = messages;
            this.writeTime = writeTime;
        }
    }

    // ========== 内部持久化逻辑 ==========

    private List<ChatMessage> loadFromDb(String sessionId) {
        List<ChatMessageRecord> records = mapper.selectBySessionId(sessionId);
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessageRecord record : records) {
            try {
                ChatMessage msg = ChatMessageDeserializer.messageFromJson(record.getContent());
                messages.add(msg);
            } catch (Exception e) {
                log.warn("[ChatMemory] 反序列化消息失败, sessionId={}, seq={}", sessionId, record.getSeq(), e);
            }
        }
        log.debug("[ChatMemory] 读取会话记忆: sessionId={}, {}条消息", sessionId, messages.size());
        return messages;
    }

    private void persistToDb(String sessionId, List<ChatMessage> messages) {
        // 框架层已做滑动窗口，这里做二次兜底
        List<ChatMessage> recent = messages;
        if (messages.size() > MAX_MESSAGES) {
            recent = messages.subList(messages.size() - MAX_MESSAGES, messages.size());
        }

        // 先删后插，保证数据一致
        mapper.deleteBySessionId(sessionId);

        List<ChatMessageRecord> records = new ArrayList<>();
        for (int i = 0; i < recent.size(); i++) {
            ChatMessage msg = recent.get(i);
            String role = msg.type() == AI ? "AI" : msg.type() == USER ? "USER" : "SYSTEM";
            String content = ChatMessageSerializer.messageToJson(msg);
            records.add(ChatMessageRecord.builder()
                    .sessionId(sessionId)
                    .seq(i)
                    .role(role)
                    .content(content)
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        for (ChatMessageRecord record : records) {
            mapper.insert(record);
        }

        log.debug("[ChatMemory] 持久化会话记忆: sessionId={}, {}条消息", sessionId, records.size());
    }
}
