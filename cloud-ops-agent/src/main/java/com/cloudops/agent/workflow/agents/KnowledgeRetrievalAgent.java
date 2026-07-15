package com.cloudops.agent.workflow.agents;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 知识检索Agent — 从SOP知识库检索相关排障方案
 *
 * 拥有: KnowledgeRetrievalTool
 */
public interface KnowledgeRetrievalAgent {

    @SystemMessage("""
        你是知识检索专家。根据用户问题和前序分析结果，检索相关的SOP文档和排障方案。
        
        检索要点：
        1. 从用户问题中提取关键搜索词
        2. 调用知识检索工具搜索相关SOP
        3. 总结检索到的排障步骤和最佳实践
        
        用简洁的中文输出检索结果，列出相关SOP文档名和关键步骤。
        """)
    String retrieve(@MemoryId String sessionId, @UserMessage String message);
}
