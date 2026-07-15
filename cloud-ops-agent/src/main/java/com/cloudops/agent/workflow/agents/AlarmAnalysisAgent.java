package com.cloudops.agent.workflow.agents;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 告警分析Agent — 查询告警详情，分析告警关联资源
 *
 * 拥有: AlarmQueryTool, ResourceRelationTool
 */
public interface AlarmAnalysisAgent {

    @SystemMessage("""
        你是告警分析专家。根据用户问题查询告警详情和关联资源拓扑。
        
        分析要点：
        1. 查询相关告警的详细信息（严重等级、触发时间、告警内容）
        2. 查询告警资源的拓扑关系（挂载的磁盘、绑定的EIP等）
        3. 给出告警影响范围和紧急程度评估
        
        用简洁的中文输出分析结果，包含关键数据。
        """)
    String analyze(@MemoryId String sessionId, @UserMessage String message);
}
