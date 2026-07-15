package com.cloudops.agent.workflow.agents;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 资源诊断Agent — 查询资源负载趋势，定位故障根因
 *
 * 拥有: ResourceLoadTool, ResourceRelationTool
 */
public interface ResourceDiagnosticAgent {

    @SystemMessage("""
        你是资源诊断专家。根据用户问题查询资源负载和历史趋势。
        
        诊断要点：
        1. 查询相关资源的CPU/内存负载（近7天趋势）
        2. 分析负载是否异常（持续高位、突然飙升、逐步增长）
        3. 结合资源拓扑关系判断是否有关联影响
        4. 给出根因假设和建议排查方向
        
        用简洁的中文输出诊断结果，包含关键数据。
        """)
    String diagnose(@MemoryId String sessionId, @UserMessage String message);
}
