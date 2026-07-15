package com.cloudops.controller;

import com.cloudops.rag.DocumentIngestService;
import com.cloudops.rag.KnowledgeChunk;
import com.cloudops.rag.KnowledgeRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RAG 接口 — 入库 + 检索
 *
 * 入库：GET /api/rag/ingest  首次启动调一次，把 23 篇文档灌入 Milvus
 * 检索：GET /api/rag/search  验证检索效果，支持三种模式
 *       ?query=CPU高&mode=hybrid  （默认混合检索）
 *       ?query=CPU高&mode=vector  （纯向量检索）
 *       ?query=CPU高&mode=keyword （纯关键词检索）
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final DocumentIngestService documentIngestService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * 手动触发文档入库
     * 调用：GET /api/rag/ingest
     */
    @GetMapping("/ingest")
    @PreAuthorize("hasAuthority('rag:ingest')")
    public Map<String, Object> ingest() {
        int count = documentIngestService.ingestAll();
        return Map.of(
                "status", "success",
                "documentsIngested", count,
                "message", "文档入库完成，调用 /api/rag/search?query=CPU高 验证检索"
        );
    }

    /**
     * 知识检索接口
     * 调用：GET /api/rag/search?query=CPU高&mode=hybrid
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('rag:read')")
    public Map<String, Object> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "hybrid") String mode
    ) {
        List<KnowledgeChunk> chunks;
        switch (mode) {
            case "vector":
                chunks = knowledgeRetrievalService.vectorSearch(query);
                break;
            case "keyword":
                chunks = knowledgeRetrievalService.keywordSearch(query);
                break;
            case "hybrid":
            default:
                chunks = knowledgeRetrievalService.hybridSearch(query);
                break;
        }

        return Map.of(
                "query", query,
                "mode", mode,
                "count", chunks.size(),
                "results", chunks
        );
    }
}
