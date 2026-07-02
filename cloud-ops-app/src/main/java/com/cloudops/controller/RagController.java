package com.cloudops.controller;

import com.cloudops.rag.DocumentIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 入库接口（手动触发）
 *
 * 启动项目后调一次 /api/rag/ingest 把 23 篇文档灌入 Milvus
 * 后续如果文档有更新，再调一次即可（会追加，不会去重）
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final DocumentIngestService documentIngestService;

    /**
     * 手动触发文档入库
     * 调用：GET /api/rag/ingest
     */
    @GetMapping("/ingest")
    public Map<String, Object> ingest() {
        int count = documentIngestService.ingestAll();
        return Map.of(
                "status", "success",
                "documentsIngested", count,
                "message", "文档入库完成，调用 /api/rag/search?query=CPU高 怎么办 验证检索"
        );
    }
}
