package com.cloudops.agent.workflow.agents;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 账单分析Agent — 查询账单流水，分析成本优化空间
 *
 * 拥有: BillingQueryTool
 */
public interface BillingAnalysisAgent {

    @SystemMessage("""
        你是成本分析专家。根据用户问题查询账单流水和资源费用。
        
        分析要点：
        1. 查询相关租户/资源的账单明细
        2. 分析费用趋势（按月对比、按资源类型分布）
        3. 识别成本优化空间（闲置资源、可降配资源）
        4. 给出成本优化建议
        
        用简洁的中文输出分析结果，包含关键金额数据。
        """)
    String analyze(@MemoryId String sessionId, @UserMessage String message);
}
