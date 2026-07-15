package com.cloudops.agent.workflow.context;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作流共享上下文 — Blackboard 模式
 *
 * 每个 Agent 执行后，将中间结果写入此对象
 * 后续 Agent 可读取前序 Agent 的结果作为输入
 *
 * 设计为可变对象（非 record），因为需要逐步填充字段
 */
@Data
public class WorkflowContext {

    // === 输入 ===
    private final String sessionId;        // 会话ID（tenantId:userId）
    private final String userMessage;      // 用户原始问题
    // === Step 3: 执行轨迹 ===
    private final List<AgentExecutionRecord> executionTrace = new ArrayList<>();
    // === Step 1: 分诊结果 ===
    private TriageResult triageResult;
    // === Step 2: 各专业Agent的文本结果 ===
    // 用 String 而非复杂对象，因为各Agent返回的是自然语言分析文本
    private String alarmAnalysis;          // 告警分析结果
    private String resourceDiagnosis;      // 资源诊断结果
    private String billingAnalysis;        // 账单分析结果
    private String knowledgeResult;        // 知识检索结果

    /** 记录Agent执行 */
    public void recordExecution(String agentName, long durationMs) {
        executionTrace.add(new AgentExecutionRecord(agentName, Instant.now(), durationMs));
    }
}
