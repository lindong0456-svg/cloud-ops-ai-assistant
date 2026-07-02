package com.cloudops.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识检索服务 — RAG 检索核心
 *
 * 数据流：用户问题 → 向量检索 + 关键词检索 → RRF 融合 → top-3 返回
 *
 * 三种检索方式：
 *   1. vectorSearch  — 语义相似度，调 Milvus 查 top-5
 *   2. keywordSearch — 关键词命中，文件名 LIKE 匹配（轻量方案，不引入 ES）
 *   3. hybridSearch  — RRF 融合两路结果，取 top-3
 *
 * RRF（Reciprocal Rank Fusion）公式：
 *   score = 1/(60 + rank_vector) + 1/(60 + rank_keyword)
 *   60 是平滑常数，避免排名靠前的结果分数过大（Elastic 官方推荐值）
 *
 * 设计取舍：
 *   - 不接外部 Rerank API（如 Cohere Rerank），省一次网络调用
 *   - 关键词检索只用文件名匹配，23 篇文档量小够用
 *   - 面试可讲"按场景演进：文档量上千后关键词检索换 ES，Rerank 换 Cohere"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${rag.top-k:5}")
    private int topK;

    @Value("${rag.rerank-top-k:3}")
    private int rerankTopK;

    @Value("${rag.docs-path:/Users/linguoyong/Desktop/cloudops-runbooks}")
    private String docsPath;

    /**
     * 向量检索 — 语义相似度
     *
     * 流程：query → 百炼向量化 → Milvus 查 top-5 → 转 KnowledgeChunk
     */
    public List<KnowledgeChunk> vectorSearch(String query) {
        long startTime = System.currentTimeMillis();
        try {
            // 1. query 转向量
            Response<Embedding> embedResp = embeddingModel.embed(query);
            Embedding queryEmbedding = embedResp.content();

            // 2. 调 Milvus 检索 top-5
            EmbeddingSearchRequest searchReq = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(0.0)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchReq);

            // 3. 转成 KnowledgeChunk
            List<KnowledgeChunk> chunks = searchResult.matches().stream()
                    .map(match -> toChunk(match, 0))
                    .collect(Collectors.toList());

            long costMs = System.currentTimeMillis() - startTime;
            log.info("[RAG] 向量检索完成, query='{}', 命中{}条, 耗时{}ms", query, chunks.size(), costMs);
            return chunks;

        } catch (Exception e) {
            log.error("[RAG] 向量检索失败, query='{}', 错误: {}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 关键词检索 — 文件名 LIKE 匹配
     *
     * 策略：把 query 拆成关键词，匹配文档文件名，命中则加载该文档全部内容。
     * 例如 query="cpu 高" → 拆出 cpu → 匹配 alert-cpu-high.md
     *
     * 轻量方案，23 篇文档够用；上千篇后换 ES 全文检索。
     */
    public List<KnowledgeChunk> keywordSearch(String query) {
        long startTime = System.currentTimeMillis();
        try {
            // 1. query 拆词（中英文混合，按空格+标点切，转小写）
            List<String> keywords = tokenize(query);
            if (keywords.isEmpty()) {
                return Collections.emptyList();
            }

            // 2. 遍历文档目录，文件名命中关键词的加载
            List<KnowledgeChunk> chunks = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(Paths.get(docsPath))) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".md"))
                     .sorted()
                     .forEach(p -> {
                         String fileName = p.getFileName().toString().toLowerCase();
                         int hits = countHits(fileName, keywords);
                         if (hits > 0) {
                             try {
                                 String content = Files.readString(p);
                                 chunks.add(KnowledgeChunk.builder()
                                         .content(content)
                                         .source(p.getFileName().toString())
                                         .score(0.0)
                                         .keywordHits(hits)
                                         .build());
                             } catch (IOException e) {
                                 log.error("[RAG] 关键词检索读取文件失败: {}", p, e);
                             }
                         }
                     });
            }

            // 3. 按命中数降序
            chunks.sort(Comparator.comparingInt(KnowledgeChunk::getKeywordHits).reversed());

            long costMs = System.currentTimeMillis() - startTime;
            log.info("[RAG] 关键词检索完成, query='{}', keywords={}, 命中{}篇, 耗时{}ms",
                    query, keywords, chunks.size(), costMs);
            return chunks;

        } catch (Exception e) {
            log.error("[RAG] 关键词检索失败, query='{}', 错误: {}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 混合检索 — RRF 融合向量 + 关键词两路结果
     *
     * RRF 公式：score = 1/(60 + rank_v) + 1/(60 + rank_k)
     *   rank 从 1 开始，排名越靠前分数越高
     *   60 是平滑常数（Elastic 官方推荐）
     *
     * 去重策略：按 source（文件名）+ content 前100字符 做 key 去重
     */
    public List<KnowledgeChunk> hybridSearch(String query) {
        long startTime = System.currentTimeMillis();
        log.info("[RAG] 混合检索开始, query='{}'", query);

        // 1. 两路并行检索
        List<KnowledgeChunk> vectorResults = vectorSearch(query);
        List<KnowledgeChunk> keywordResults = keywordSearch(query);

        // 2. RRF 融合
        Map<String, KnowledgeChunk> fusedMap = new LinkedHashMap<>();
        final int RRF_K = 60; // 平滑常数

        // 向量结果按 score 降序排（Milvus 返回的已排序，保险起见再排一次）
        vectorResults.sort(Comparator.comparingDouble(KnowledgeChunk::getVectorScore).reversed());
        for (int i = 0; i < vectorResults.size(); i++) {
            KnowledgeChunk chunk = vectorResults.get(i);
            String key = dedupKey(chunk);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            chunk.setScore(chunk.getScore() + rrfScore);
            fusedMap.put(key, chunk);
        }

        // 关键词结果按命中数降序排
        keywordResults.sort(Comparator.comparingInt(KnowledgeChunk::getKeywordHits).reversed());
        for (int i = 0; i < keywordResults.size(); i++) {
            KnowledgeChunk chunk = keywordResults.get(i);
            String key = dedupKey(chunk);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            if (fusedMap.containsKey(key)) {
                // 已在向量结果中，累加 RRF 分数
                KnowledgeChunk existing = fusedMap.get(key);
                existing.setScore(existing.getScore() + rrfScore);
                existing.setKeywordHits(chunk.getKeywordHits());
            } else {
                chunk.setScore(rrfScore);
                fusedMap.put(key, chunk);
            }
        }

        // 3. 按 RRF 分数降序，取 top-3
        List<KnowledgeChunk> result = fusedMap.values().stream()
                .sorted(Comparator.comparingDouble(KnowledgeChunk::getScore).reversed())
                .limit(rerankTopK)
                .collect(Collectors.toList());

        long costMs = System.currentTimeMillis() - startTime;
        log.info("[RAG] 混合检索完成, query='{}', 融合后{}条, 耗时{}ms", query, result.size(), costMs);
        return result;
    }

    /**
     * EmbeddingMatch → KnowledgeChunk 转换
     */
    private KnowledgeChunk toChunk(EmbeddingMatch<TextSegment> match, int keywordHits) {
        TextSegment segment = match.embedded();
        String source = segment.metadata().getString("source");
        if (source == null) {
            source = "unknown";
        }
        return KnowledgeChunk.builder()
                .content(segment.text())
                .source(source)
                .score(0.0)
                .vectorScore(match.score() != null ? match.score() : 0.0)
                .keywordHits(keywordHits)
                .build();
    }

    /**
     * 去重 key：source + content 前100字符
     * 同一文档的不同分块内容不同，不合并；完全相同的块才去重
     */
    private String dedupKey(KnowledgeChunk chunk) {
        String contentPrefix = chunk.getContent() != null && chunk.getContent().length() > 100
                ? chunk.getContent().substring(0, 100)
                : chunk.getContent();
        return chunk.getSource() + "|" + contentPrefix;
    }

    /**
     * query 拆词 — 中英文混合
     * 英文按空格切，中文按字符切（简易分词，够用）
     */
    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        // 转小写，按非字母数字字符切分
        return Arrays.stream(query.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+"))
                .filter(s -> !s.isBlank())
                .filter(s -> s.length() >= 2) // 过滤单字符噪声
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 计算文件名命中关键词的数量
     */
    private int countHits(String fileName, List<String> keywords) {
        int hits = 0;
        for (String kw : keywords) {
            if (fileName.contains(kw)) {
                hits++;
            }
        }
        return hits;
    }
}
