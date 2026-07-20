package com.cloudops.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级可观测性指标累加器 — 跨线程安全（userId 键，同 RequestContextStore 模式）
 *
 * 解决的问题：
 *   在一次 chatStream 请求内，RAG 检索与多个工具调用分散在不同线程/组件执行，
 *   需要把它们的指标累加到一个“本次响应”的快照里，在流式结束（done 事件）时
 *   一次性回传给前端 stats-bar 展示。
 *
 * 后端同时有 Micrometer（Grafana）指标：
 *   - tool.call.duration / tool.call.total   （AbstractTool 已埋点）
 *   - rag.call.duration / rag.recall.chunks  （KnowledgeRetrievalService 埋点）
 * 本累加器补充“每次响应”的明细，让前端也能看到本次排障的检索命中数与工具成功率。
 *
 * 并发说明：以 userId 为键（与 RequestContextStore 一致的成熟模式）。
 *   同一用户并发请求会共享同一快照——对当前 Demo 量级可接受，需要时可升级为请求级 requestId。
 */
public class RequestMetrics {

    private static final Map<String, Snapshot> STORE = new ConcurrentHashMap<>();

    /** 请求开始：初始化空快照（幂等，重复调用覆盖） */
    public static void create(String userId) {
        if (userId != null) STORE.put(userId, new Snapshot());
    }

    /** 读取快照（不存在返回 null） */
    public static Snapshot get(String userId) {
        return userId != null ? STORE.get(userId) : null;
    }

    /** 记录 RAG 检索指标 */
    public static void recordRag(String userId, int recallCount, long latencyMs, int rerankInput, int rerankOutput) {
        Snapshot s = userId != null ? STORE.get(userId) : null;
        if (s == null) return;
        s.ragRecallCount += recallCount;
        s.ragLatencyMs += latencyMs;
        s.ragRerankInput = rerankInput;
        s.ragRerankOutput = rerankOutput;
    }

    /** 记录一次工具调用结果 */
    public static void recordTool(String userId, String toolName, boolean success) {
        Snapshot s = userId != null ? STORE.get(userId) : null;
        if (s == null) return;
        if (success) {
            s.toolSuccess++;
            s.toolDetails.add(toolName + "✓");
        } else {
            s.toolFail++;
            s.toolDetails.add(toolName + "✗");
        }
    }

    /** 请求结束：清除快照 */
    public static void remove(String userId) {
        if (userId != null) STORE.remove(userId);
    }

    /** 单次请求的指标快照 */
    public static class Snapshot {
        public final List<String> toolDetails = new ArrayList<>(); // 如 "searchKnowledge✓" / "queryAlarm✗"
        public int ragRecallCount = 0;       // RAG 检索命中的 chunk 数（累加）
        public long ragLatencyMs = 0;        // RAG 检索端到端耗时（累加）
        public int ragRerankInput = 0;       // Rerank 输入候选数
        public int ragRerankOutput = 0;      // Rerank 输出数（精排后 top-N）
        public int toolSuccess = 0;          // 工具调用成功次数
        public int toolFail = 0;             // 工具调用失败次数

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ragRecallCount", ragRecallCount);
            m.put("ragLatencyMs", ragLatencyMs);
            m.put("ragRerankInput", ragRerankInput);
            m.put("ragRerankOutput", ragRerankOutput);
            m.put("toolSuccess", toolSuccess);
            m.put("toolFail", toolFail);
            m.put("toolDetails", new ArrayList<>(toolDetails));
            return m;
        }
    }
}
