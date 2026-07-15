package com.cloudops.agent.workflow;

import com.cloudops.agent.workflow.agents.*;
import com.cloudops.agent.workflow.context.IssueType;
import com.cloudops.agent.workflow.context.TriageResult;
import com.cloudops.agent.workflow.context.WorkflowContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作流编排器 — 驱动6个Agent按预设流程协作
 *
 * 工作流定义:
 *   1. TriageAgent 分诊 → 输出 IssueType (ALARM/RESOURCE/COST/GENERAL)
 *   2. 根据 IssueType 路由:
 *      - ALARM → AlarmAnalysisAgent → ResourceDiagnosticAgent
 *      - RESOURCE → ResourceDiagnosticAgent
 *      - COST → BillingAnalysisAgent
 *      - GENERAL → (直接进入知识检索)
 *   3. KnowledgeRetrievalAgent 知识检索（所有类型都执行）
 *   4. SynthesisAgent 综合分析 → 最终报告
 *
 * 每个Agent执行后，结果写入 WorkflowContext（共享黑板）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private final TriageAgent triageAgent;
    private final AlarmAnalysisAgent alarmAgent;
    private final ResourceDiagnosticAgent resourceAgent;
    private final KnowledgeRetrievalAgent knowledgeAgent;
    private final BillingAnalysisAgent billingAgent;
    private final SynthesisAgent synthesisAgent;
    private final ObjectMapper objectMapper;

    /**
     * 执行工作流（同步）
     *
     * @param sessionId   会话ID（tenantId:userId）
     * @param userMessage 用户原始问题
     * @return 最终排障报告
     */
    public WorkflowResult execute(String sessionId, String userMessage) {
        long startTime = System.currentTimeMillis();
        log.info("[Workflow] 开始执行, sessionId={}, message={}", sessionId, truncate(userMessage));

        // === Step 1: 分诊 ===
        WorkflowContext context = new WorkflowContext(sessionId, userMessage);
        TriageResult triage = runTriage(sessionId, userMessage, context);
        context.setTriageResult(triage);
        log.info("[Workflow] 分诊完成: type={}, priority={}", triage.issueType(), triage.priority());

        // === Step 2: 按类型路由 ===
        switch (triage.issueType()) {
            case ALARM -> {
                String alarmResult = runAlarmAnalysis(sessionId, userMessage, context);
                context.setAlarmAnalysis(alarmResult);

                String resourceResult = runResourceDiagnosis(sessionId, userMessage, context);
                context.setResourceDiagnosis(resourceResult);
            }
            case RESOURCE -> {
                String resourceResult = runResourceDiagnosis(sessionId, userMessage, context);
                context.setResourceDiagnosis(resourceResult);
            }
            case COST -> {
                String billingResult = runBillingAnalysis(sessionId, userMessage, context);
                context.setBillingAnalysis(billingResult);
            }
            case GENERAL -> {
                // 直接进入知识检索
            }
        }

        // === Step 3: 知识检索（所有类型都执行）===
        String knowledgeResult = runKnowledgeRetrieval(sessionId, userMessage, context);
        context.setKnowledgeResult(knowledgeResult);

        // === Step 4: 综合分析 ===
        String finalReport = runSynthesis(sessionId, context);

        long totalMs = System.currentTimeMillis() - startTime;
        log.info("[Workflow] 执行完成, 总耗时={}ms, 步骤数={}", totalMs, context.getExecutionTrace().size());

        return new WorkflowResult(finalReport, context);
    }

    /**
     * 执行工作流（流式）— Step 1~3同步，Step 4流式输出
     */
    public TokenStream executeStream(String sessionId, String userMessage) {
        long startTime = System.currentTimeMillis();
        log.info("[Workflow] 开始流式执行, sessionId={}", sessionId);

        // Step 1~3 同步执行
        WorkflowContext context = new WorkflowContext(sessionId, userMessage);
        TriageResult triage = runTriage(sessionId, userMessage, context);
        context.setTriageResult(triage);

        switch (triage.issueType()) {
            case ALARM -> {
                context.setAlarmAnalysis(runAlarmAnalysis(sessionId, userMessage, context));
                context.setResourceDiagnosis(runResourceDiagnosis(sessionId, userMessage, context));
            }
            case RESOURCE -> {
                context.setResourceDiagnosis(runResourceDiagnosis(sessionId, userMessage, context));
            }
            case COST -> {
                context.setBillingAnalysis(runBillingAnalysis(sessionId, userMessage, context));
            }
            case GENERAL -> {}
        }

        context.setKnowledgeResult(runKnowledgeRetrieval(sessionId, userMessage, context));

        // Step 4 流式输出
        String synthesisInput = buildSynthesisInput(context);
        log.info("[Workflow] 前置步骤完成, 开始流式综合分析");
        return synthesisAgent.synthesizeStream(agentMemoryId(sessionId, "synthesis"), synthesisInput);
    }

    // ===== 各 Agent 执行方法 =====

    /**
     * 为每个 Agent 生成独立的 memoryId，隔离 ChatMemory。
     *
     * ★ 根因修复：原先所有 Agent 共享同一个 sessionId，导致 AlarmAnalysisAgent 产生的
     *   tool_calls（调用 alarmQuery）残留在共享 memory 中。当 ResourceDiagnosticAgent
     *   或 TriageAgent（无 Tool）加载这份 memory 时，OpenAI API 报 400:
     *   "An assistant message with 'tool_calls' must be followed by tool messages"
     *
     *   每个 Agent 用专属 memoryId 后，tool_calls 不会跨 Agent 污染。
     */
    private String agentMemoryId(String sessionId, String agentName) {
        return sessionId + ":" + agentName;
    }

    private TriageResult runTriage(String sessionId, String message, WorkflowContext context) {
        long start = System.currentTimeMillis();
        String result = triageAgent.triage(agentMemoryId(sessionId, "triage"), message);
        long cost = System.currentTimeMillis() - start;
        context.recordExecution("TriageAgent", cost);
        log.info("[Workflow] TriageAgent 完成, 耗时={}ms, 输出={}", cost, truncate(result));

        // 解析 JSON → TriageResult
        return parseTriageResult(result);
    }

    private String runAlarmAnalysis(String sessionId, String message, WorkflowContext context) {
        long start = System.currentTimeMillis();
        String result = alarmAgent.analyze(agentMemoryId(sessionId, "alarm"), message);
        long cost = System.currentTimeMillis() - start;
        context.recordExecution("AlarmAnalysisAgent", cost);
        log.info("[Workflow] AlarmAnalysisAgent 完成, 耗时={}ms", cost);
        return result;
    }

    private String runResourceDiagnosis(String sessionId, String message, WorkflowContext context) {
        long start = System.currentTimeMillis();
        String result = resourceAgent.diagnose(agentMemoryId(sessionId, "resource"), message);
        long cost = System.currentTimeMillis() - start;
        context.recordExecution("ResourceDiagnosticAgent", cost);
        log.info("[Workflow] ResourceDiagnosticAgent 完成, 耗时={}ms", cost);
        return result;
    }

    private String runKnowledgeRetrieval(String sessionId, String message, WorkflowContext context) {
        long start = System.currentTimeMillis();
        // 拼入分诊关键词，让知识检索Agent更精准
        String enrichedQuery = message;
        if (context.getTriageResult() != null && context.getTriageResult().keywords() != null) {
            enrichedQuery = message + "\n关键词: " + String.join(", ", context.getTriageResult().keywords());
        }
        String result = knowledgeAgent.retrieve(agentMemoryId(sessionId, "knowledge"), enrichedQuery);
        long cost = System.currentTimeMillis() - start;
        context.recordExecution("KnowledgeRetrievalAgent", cost);
        log.info("[Workflow] KnowledgeRetrievalAgent 完成, 耗时={}ms", cost);
        return result;
    }

    private String runBillingAnalysis(String sessionId, String message, WorkflowContext context) {
        long start = System.currentTimeMillis();
        String result = billingAgent.analyze(agentMemoryId(sessionId, "billing"), message);
        long cost = System.currentTimeMillis() - start;
        context.recordExecution("BillingAnalysisAgent", cost);
        log.info("[Workflow] BillingAnalysisAgent 完成, 耗时={}ms", cost);
        return result;
    }

    private String runSynthesis(String sessionId, WorkflowContext context) {
        long start = System.currentTimeMillis();
        String synthesisInput = buildSynthesisInput(context);
        String result = synthesisAgent.synthesize(agentMemoryId(sessionId, "synthesis"), synthesisInput);
        long cost = System.currentTimeMillis() - start;
        context.recordExecution("SynthesisAgent", cost);
        log.info("[Workflow] SynthesisAgent 完成, 耗时={}ms", cost);
        return result;
    }

    // ===== 辅助方法 =====

    /**
     * 构建综合Agent的输入 — 把前序所有Agent的结果拼成一段文本
     */
    private String buildSynthesisInput(WorkflowContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户原始问题: ").append(context.getUserMessage()).append("\n\n");

        if (context.getTriageResult() != null) {
            sb.append("分诊结果: 类型=").append(context.getTriageResult().issueType())
                    .append(", 摘要=").append(context.getTriageResult().summary()).append("\n\n");
        }

        if (context.getAlarmAnalysis() != null) {
            sb.append("=== 告警分析结果 ===\n").append(context.getAlarmAnalysis()).append("\n\n");
        }

        if (context.getResourceDiagnosis() != null) {
            sb.append("=== 资源诊断结果 ===\n").append(context.getResourceDiagnosis()).append("\n\n");
        }

        if (context.getBillingAnalysis() != null) {
            sb.append("=== 账单分析结果 ===\n").append(context.getBillingAnalysis()).append("\n\n");
        }

        if (context.getKnowledgeResult() != null) {
            sb.append("=== 知识检索结果 ===\n").append(context.getKnowledgeResult()).append("\n\n");
        }

        sb.append("请根据以上分析结果，生成结构化排障报告。");
        return sb.toString();
    }

    /**
     * 解析TriageAgent的JSON输出为TriageResult
     * 兼容LLM可能输出markdown代码块的情况
     */
    private TriageResult parseTriageResult(String json) {
        try {
            // 去掉可能的 markdown 代码块标记
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "");
            }
            var node = objectMapper.readTree(clean);
            return new TriageResult(
                    IssueType.valueOf(node.get("issueType").asText()),
                    node.get("summary").asText(),
                    node.get("priority").asInt(),
                    objectMapper.convertValue(node.get("keywords"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class))
            );
        } catch (Exception e) {
            log.warn("[Workflow] 分诊结果解析失败, 降级为GENERAL: {}", e.getMessage());
            return new TriageResult(IssueType.GENERAL, "解析失败: " + truncate(json), 3, List.of());
        }
    }

    private String truncate(String s) {
        return s != null && s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    public TriageAgent getTriageAgent() { return triageAgent; }
    public AlarmAnalysisAgent getAlarmAgent() { return alarmAgent; }
    public ResourceDiagnosticAgent getResourceAgent() { return resourceAgent; }
    public KnowledgeRetrievalAgent getKnowledgeAgent() { return knowledgeAgent; }
    public BillingAnalysisAgent getBillingAgent() { return billingAgent; }
    public SynthesisAgent getSynthesisAgent() { return synthesisAgent; }
    public TriageResult parseTriage(String json) { return parseTriageResult(json); }


    // ===== 工作流结果 =====

    /**
     * 工作流执行结果
     */
    public record WorkflowResult(
            String finalReport,
            WorkflowContext context
    ) {}
}
