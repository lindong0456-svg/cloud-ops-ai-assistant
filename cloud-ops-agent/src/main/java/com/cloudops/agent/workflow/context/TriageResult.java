package com.cloudops.agent.workflow.context;

import java.util.List;

/**
 * 分诊结果 — TriageAgent 的输出
 *
 * TriageAgent 是纯 LLM 推理（无 Tool），分析用户问题后输出类型+摘要+关键词
 */
public record TriageResult(
        IssueType issueType,     // 问题类型
        String summary,          // 一句话摘要
        int priority,            // 优先级 1-5
        List<String> keywords    // 提取的关键词（供后续Agent使用）
) {}
