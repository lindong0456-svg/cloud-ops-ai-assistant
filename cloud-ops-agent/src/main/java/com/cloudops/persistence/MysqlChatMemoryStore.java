package com.cloudops.persistence;

import com.cloudops.entity.ChatMessageRecord;
import com.cloudops.mapper.ChatMessageMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.data.message.ChatMessageType.AI;
import static dev.langchain4j.data.message.ChatMessageType.USER;

/**
 * MySQL 对话记忆存储
 * <p>
 * ChatMemoryStore 接口方法被 LangChain4j 框架调用，调用时机为每次 Agent 完成一轮对话后。
 * MysqlChatMemoryStore 负责将消息列表序列化写入 chat_memory 表，查询时反序列化还原。
 * <p>
 * 安全边界：框架层 MessageWindowChatMemory 已做 20 轮滑动窗口截断，
 * updateMessages 传入的 messages 已经是截断后的列表。MySQL 层做二次兜底：
 * 如果传入超过 20 条，只保留最近 20 条。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_MESSAGES = 50;
    private final ChatMessageMapper mapper;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
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

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();

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

        // 批量插入
        for (ChatMessageRecord record : records) {
            mapper.insert(record);
        }

        log.debug("[ChatMemory] 持久化会话记忆: sessionId={}, {}条消息", sessionId, records.size());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        int deleted = mapper.deleteBySessionId(sessionId);
        log.debug("[ChatMemory] 删除会话记忆: sessionId={}, 删除{}条", sessionId, deleted);
    }
}
