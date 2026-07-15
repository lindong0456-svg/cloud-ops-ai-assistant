package com.cloudops.agent.workflow.context;

/**
 * 问题分类 — TriageAgent 的输出，决定工作流路由
 */
public enum IssueType {
    ALARM,      // 告警排障（CPU高、磁盘满、OOM等）
    RESOURCE,   // 资源诊断（负载异常、性能问题）
    COST,       // 成本分析（账单查询、成本优化）
    GENERAL     // 通用咨询（SOP查询、最佳实践）
}
