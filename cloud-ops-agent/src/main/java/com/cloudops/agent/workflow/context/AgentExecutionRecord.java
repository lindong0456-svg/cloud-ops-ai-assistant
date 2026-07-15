package com.cloudops.agent.workflow.context;

import java.time.Instant;

/**
 * Agent 执行记录 — 用于追踪工作流执行过程
 */
public record AgentExecutionRecord(
        String agentName,        // Agent名称
        Instant timestamp,       // 执行时间
        long durationMs          // 执行耗时
) {}
