package com.cloudops.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SOP 文档查看接口
 *
 * 排障结论里引用的 .md 文档名（如 sop-cpu-high-troubleshooting.md），
 * 前端点击后调用此接口获取文档全文，弹窗展示。
 *
 * 安全：只允许读取 rag.docs-path 目录下的 .md 文件，防止路径穿越。
 */
@Slf4j
@RestController
@RequestMapping("/api/sop")
public class SopController {

    @Value("${rag.docs-path:/Users/linguoyong/Desktop/cloudops-runbooks}")
    private String docsPath;

    /**
     * 获取 SOP 文档内容
     * 调用：GET /api/sop/alert-cpu-high.md
     *
     * @param docName 文档名，如 alert-cpu-high.md
     * @return 文档纯文本内容（Markdown 格式）
     */
    @GetMapping(value = "/{docName}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getSopDoc(@PathVariable String docName) {
        log.info("[SOP] 请求文档: {}", docName);

        // 安全校验：只允许 .md 文件，禁止路径穿越
        if (!docName.endsWith(".md") || docName.contains("..") || docName.contains("/")) {
            log.warn("[SOP] 非法文档名: {}", docName);
            return "[错误] 文档名不合法，只允许 .md 文件";
        }

        Path docPath = Paths.get(docsPath, docName);

        if (!Files.exists(docPath)) {
            log.warn("[SOP] 文档不存在: {}", docPath);
            return "[错误] 文档不存在: " + docName;
        }

        try {
            String content = Files.readString(docPath);
            log.info("[SOP] 文档读取成功: {} ({}字符)", docName, content.length());
            return content;
        } catch (IOException e) {
            log.error("[SOP] 读取文档失败: {}", docName, e);
            return "[错误] 读取文档失败: " + e.getMessage();
        }
    }
}
