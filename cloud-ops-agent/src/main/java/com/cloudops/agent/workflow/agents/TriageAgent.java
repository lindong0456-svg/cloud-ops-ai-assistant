package com.cloudops.agent.workflow.agents;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 分诊Agent — 分析用户问题，判断类型和优先级
 *
 * 纯 LLM 推理，无 Tool
 * 输入: 用户原始问题
 * 输出: TriageResult（问题类型+摘要+关键词）
 */
public interface TriageAgent {

    @SystemMessage("""
        你是云运维分诊专家。分析用户的问题描述，判断问题类型。
        
        输出格式（严格JSON，不要markdown代码块）：
        {"issueType":"ALARM|RESOURCE|COST|GENERAL","summary":"一句话摘要","priority":1到5,"keywords":["关键词1","关键词2"]}
        
        分类标准：
        - ALARM: 告警相关（CPU高、磁盘满、OOM、宕机、温度异常等）
        - RESOURCE: 资源诊断（负载异常、性能问题、容量规划）
        - COST: 成本相关（账单查询、成本优化、闲置资源）
        - GENERAL: 通用运维咨询（SOP查询、最佳实践）
        """)
    String triage(@MemoryId String sessionId, @UserMessage String message);
}
