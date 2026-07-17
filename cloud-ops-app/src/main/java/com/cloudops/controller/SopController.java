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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SOP 文档查看接口
 *
 * 排障结论里引用的 .md 文档名（如 sop-cpu-high-troubleshooting.md），
 * 前端点击后调用此接口获取文档全文，弹窗展示。
 *
 * 安全：只允许读取 rag.docs-path 目录下的 .md 文件，防止路径穿越。
 *
 * ★ 模糊匹配（重要）：
 *   RAG / LLM 可能返回不完全精确的文件名（如 sop-disk-io-high.md 但实际是 alert-disk-full.md）。
 *   精确匹配失败时，按以下优先级尝试模糊查找：
 *     1. 忽略大小写精确匹配
 *     2. 文件名包含关系（请求名包含在目标中，或目标包含在请求中）
 *     3. 关键词匹配（按空格/连字符拆分后取交集最多的文件）
 *   若仍找不到，返回可用文档列表供用户选择。
 */
@Slf4j
@RestController
@RequestMapping("/api/sop")
public class SopController {

    @Value("${rag.docs-path:./docs/runbooks}")
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

        Path docsDir = Paths.get(docsPath);
        Path exactPath = docsDir.resolve(docName);

        // 1. 精确匹配
        if (Files.exists(exactPath)) {
            return readDoc(exactPath, docName);
        }

        // 2. 精确失败 → 模糊匹配
        Path matched = fuzzyFindDoc(docsDir, docName);
        if (matched != null) {
            log.info("[SOP] 模糊匹配成功: '{}' -> '{}'", docName, matched.getFileName());
            return readDoc(matched, matched.getFileName().toString());
        }

        // 3. 完全找不到 → 返回可用文档列表
        log.warn("[SOP] 文档不存在且无模糊匹配: {}，返回可用文档列表", docName);
        return buildNotFoundResponse(docsDir, docName);
    }

    /**
     * 模糊查找文档：多级策略
     */
    private Path fuzzyFindDoc(Path docsDir, String docName) {
        try {
            if (!Files.isDirectory(docsDir)) return null;

            String target = docName.toLowerCase();
            String targetStem = target.replace(".md", "");

            List<Path> candidates = new ArrayList<>();
            Files.list(docsDir)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(candidates::add);

            // 策略 1：忽略大小写的精确匹配
            for (Path p : candidates) {
                String fname = p.getFileName().toString().toLowerCase();
                if (fname.equals(target)) {
                    return p;  // 大小写不同但文件名完全一致
                }
            }

            // 策略 2：包含关系匹配
            Path bestContain = null;
            int bestContainScore = 0;
            for (Path p : candidates) {
                String fname = p.getFileName().toString().toLowerCase();
                String fstem = fname.replace(".md", "");
                if (fname.contains(target) || target.contains(fname)) {
                    return p;  // 一个完全包含另一个
                }
                // 计算关键词重叠度
                int score = keywordOverlapScore(targetStem, fstem);
                if (score > bestContainScore) {
                    bestContainScore = score;
                    bestContain = p;
                }
            }

            // 只有当重叠度足够高时才返回（至少有一个完整词匹配）
            if (bestContainScore >= 1) {
                return bestContain;
            }

        } catch (IOException e) {
            log.warn("[SOP] 模糊匹配遍历目录失败", e);
        }
        return null;
    }

    /**
     * 计算两个文件名（去掉前缀和扩展名后）的关键词重叠度。
     * 按 [-_] 分割为单词，计算共同单词数。
     */
    private static int keywordOverlapScore(String a, String b) {
        String[] wordsA = a.split("[-_]");
        String[] wordsB = b.split("[-_]");
        int score = 0;
        for (String wa : wordsA) {
            if (wa.length() < 3) continue;  // 跳过太短的片段（sop、alert 等）
            for (String wb : wordsB) {
                if (wb.length() < 3) continue;
                if (wa.equals(wb)) {
                    score += 2;  // 完全匹配一个有意义的词，加分
                } else if (wa.contains(wb) || wb.contains(wa)) {
                    score += 1;  // 部分匹配，低分
                }
            }
        }
        return score;
    }

    private String readDoc(Path docPath, String docName) {
        try {
            String content = Files.readString(docPath);
            log.info("[SOP] 文档读取成功: {} ({}字符)", docName, content.length());
            return content;
        } catch (IOException e) {
            log.error("[SOP] 读取文档失败: {}", docName, e);
            return "[错误] 读取文档失败: " + e.getMessage();
        }
    }

    /**
     * 构建友好的"未找到"响应：列出所有可用文档供用户选择
     */
    private String buildNotFoundResponse(Path docsDir, String requestedDoc) {
        StringBuilder sb = new StringBuilder();
        sb.append("[错误] 文档不存在: **").append(requestedDoc).append("**\n\n");
        sb.append("> 可用的文档列表：\n\n");

        try {
            Files.list(docsDir)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        sb.append("- ").append(name).append("\n");
                    });
        } catch (IOException e) {
            sb.append("(无法列出文档目录)\n");
        }

        sb.append("\n> 请检查文件名是否正确，或从上方列表选择需要的文档。");
        return sb.toString();
    }
}
