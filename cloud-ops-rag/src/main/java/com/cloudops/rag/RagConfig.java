package com.cloudops.rag;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置 — Milvus 向量存储
 *
 * 用 LangChain4j 的 MilvusEmbeddingStore 封装，自动管理集合创建+索引。
 */
@Configuration
public class RagConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.collection-name:ops_knowledge}")
    private String collectionName;

    @Bean
    public MilvusEmbeddingStore milvusEmbeddingStore() {
        return MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .dimension(1024)               // text-embedding-v4 输出 1024 维
                .indexType(IndexType.HNSW)      // 图索引，查询快
                .metricType(MetricType.COSINE)  // 文本相似度用余弦
                .build();
    }
}
