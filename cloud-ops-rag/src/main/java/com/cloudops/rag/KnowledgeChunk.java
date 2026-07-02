package com.cloudops.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识检索片段 — 检索结果统一返回结构
 *
 * 每个 chunk 对应 SOP 文档的一个分块，包含：
 *   - content: 文本内容（给 LLM 做 grounding）
 *   - source: 来源文件名（Agent 引用溯源用）
 *   - score: 综合分数（RRF 融合后，越高越相关）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    /** 文本内容 */
    private String content;

    /** 来源文件名，如 alert-cpu-high.md */
    private String source;

    /** 综合分数，RRF 融合后越高越相关 */
    private double score;

    /** 向量检索原始分数（调试用） */
    private double vectorScore;

    /** 关键词命中数（调试用） */
    private int keywordHits;
}
