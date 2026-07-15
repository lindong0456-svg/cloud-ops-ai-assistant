package com.cloudops.config;

import com.cloudops.agent.workflow.agents.*;
import com.cloudops.rag.KnowledgeRetrievalTool;
import com.cloudops.tool.AlarmQueryTool;
import com.cloudops.tool.BillingQueryTool;
import com.cloudops.tool.ResourceLoadTool;
import com.cloudops.tool.ResourceRelationTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 多Agent配置 — 为每个Agent构建独立的 AiServices 实例
 *
 * 关键设计:
 *   1. 每个 Agent 有独立的 @SystemMessage（在接口注解中定义）
 *   2. 每个 Agent 只注册其专属 Tool（减少Tool数量，提高LLM选择准确率）
 *   3. 共享同一个 ChatLanguageModel（DeepSeek）和 StreamingChatLanguageModel
 *   4. 共享同一个 ChatMemoryProvider（按 sessionId 隔离）
 *   5. TriageAgent 和 SynthesisAgent 无 Tool（纯推理）
 *
 * Agent-Tool 映射:
 *   TriageAgent         → 无Tool（纯LLM推理）
 *   AlarmAnalysisAgent  → AlarmQueryTool, ResourceRelationTool
 *   ResourceDiagnosticAgent → ResourceLoadTool, ResourceRelationTool
 *   KnowledgeRetrievalAgent → KnowledgeRetrievalTool
 *   BillingAnalysisAgent → BillingQueryTool
 *   SynthesisAgent      → 无Tool（纯LLM推理），支持流式
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MultiAgentConfig {

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final ChatMemoryProvider chatMemoryProvider;

    // === 5个Tool Bean（由现有ToolRegistry或@Component注册） ===
    private final AlarmQueryTool alarmQueryTool;
    private final ResourceRelationTool resourceRelationTool;
    private final ResourceLoadTool resourceLoadTool;
    private final BillingQueryTool billingQueryTool;
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;

    /**
     * 分诊Agent — 纯推理，无Tool
     * 职责: 分析用户问题，输出问题类型(ALARM/RESOURCE/COST/GENERAL)
     */
    @Bean
    public TriageAgent triageAgent() {
        log.info("[MultiAgent] 构建TriageAgent（无Tool）");
        return AiServices.builder(TriageAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }

    /**
     * 告警分析Agent — 拥有告警查询+资源拓扑Tool
     * 职责: 查询告警详情，分析关联资源
     */
    @Bean
    public AlarmAnalysisAgent alarmAnalysisAgent() {
        log.info("[MultiAgent] 构建AlarmAnalysisAgent（2个Tool）");
        return AiServices.builder(AlarmAnalysisAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(List.of(alarmQueryTool, resourceRelationTool))
                .maxSequentialToolsInvocations(5)
                .build();
    }

    /**
     * 资源诊断Agent — 拥有负载查询+资源拓扑Tool
     * 职责: 查询资源负载趋势，定位故障根因
     */
    @Bean
    public ResourceDiagnosticAgent resourceDiagnosticAgent() {
        log.info("[MultiAgent] 构建ResourceDiagnosticAgent（2个Tool）");
        return AiServices.builder(ResourceDiagnosticAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(List.of(resourceLoadTool, resourceRelationTool))
                .maxSequentialToolsInvocations(5)
                .build();
    }

    /**
     * 知识检索Agent — 拥有RAG检索Tool
     * 职责: 检索SOP文档和排障方案
     */
    @Bean
    public KnowledgeRetrievalAgent knowledgeRetrievalAgent() {
        log.info("[MultiAgent] 构建KnowledgeRetrievalAgent（1个Tool）");
        return AiServices.builder(KnowledgeRetrievalAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(List.of(knowledgeRetrievalTool))
                .build();
    }

    /**
     * 账单分析Agent — 拥有账单查询Tool
     * 职责: 查询账单流水，分析成本优化
     */
    @Bean
    public BillingAnalysisAgent billingAnalysisAgent() {
        log.info("[MultiAgent] 构建BillingAnalysisAgent（1个Tool）");
        return AiServices.builder(BillingAnalysisAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(List.of(billingQueryTool))
                .maxSequentialToolsInvocations(3)
                .build();
    }

    /**
     * 综合Agent — 纯推理，无Tool，支持流式输出
     * 职责: 汇总各子Agent结果，生成最终排障报告
     */
    @Bean
    public SynthesisAgent synthesisAgent() {
        log.info("[MultiAgent] 构建SynthesisAgent（无Tool，支持流式）");
        return AiServices.builder(SynthesisAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
