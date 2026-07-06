package com.cloudops.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Rerank 精排服务 — 调用百炼 DashScope gte-rerank 模型
 *
 * RAG 两阶段检索的第二阶段：
 *   粗排（RRF 融合）返回 top-5，精排（Rerank）语义交叉打分返回 top-3
 *
 * API: POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
 * 复用现有的 DASHSCOPE_API_KEY 环境变量（与 embedding 模型同 key）
 */
@Slf4j
@Service
public class RerankService {

    private static final String RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String MODEL = "gte-rerank";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper om = new ObjectMapper();

    @Value("${langchain4j.open-ai.embedding-model.api-key}")
    private String apiKey;

    /**
     * 对候选文档做 Rerank 精排，返回 top-N
     *
     * @param query      用户问题
     * @param candidates RRF 融合后的候选文档（top-5）
     * @param topN       返回数量（3）
     * @return 精排后的 KnowledgeChunk 列表（top-3），保持原始内容不变
     */
    @SuppressWarnings("unchecked")
    public List<KnowledgeChunk> rerank(String query, List<KnowledgeChunk> candidates, int topN) {
        if (candidates.isEmpty()) return Collections.emptyList();
        long startTime = System.currentTimeMillis();

        try {
            // 1. 提取文档内容列表
            List<String> documents = candidates.stream()
                    .map(c -> c.getContent().length() > 500 ? c.getContent().substring(0, 500) : c.getContent())
                    .toList();

            // 2. 构建请求体
            Map<String, Object> input = Map.of("query", query, "documents", documents);
            Map<String, Object> params = Map.of("top_n", topN, "return_documents", false);
            Map<String, Object> body = Map.of("model", MODEL, "input", input, "parameters", params);

            String json = om.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RERANK_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Rerank] API 返回非200: status={}", response.statusCode());
                return candidates.stream().limit(topN).collect(java.util.stream.Collectors.toList());
            }

            // 3. 解析结果
            Map<String, Object> result = om.readValue(response.body(), Map.class);
            Map<String, Object> output = (Map<String, Object>) result.get("output");
            List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");

            if (results == null || results.isEmpty()) {
                return candidates.stream().limit(topN).collect(java.util.stream.Collectors.toList());
            }

            // 4. 按 rerank 索引重排
            List<KnowledgeChunk> reranked = new ArrayList<>();
            for (Map<String, Object> r : results) {
                int idx = ((Number) r.get("index")).intValue();
                double score = ((Number) r.get("relevance_score")).doubleValue();
                if (idx < candidates.size()) {
                    KnowledgeChunk chunk = candidates.get(idx);
                    chunk.setScore(score);
                    reranked.add(chunk);
                }
            }

            long costMs = System.currentTimeMillis() - startTime;
            log.info("[Rerank] 精排完成, candidates={} -> {}, 耗时{}ms", candidates.size(), reranked.size(), costMs);
            return reranked;

        } catch (Exception e) {
            log.error("[Rerank] 精排失败, 降级返回原 top-{}, error={}", topN, e.getMessage());
            return candidates.stream().limit(topN).collect(java.util.stream.Collectors.toList());
        }
    }
}
