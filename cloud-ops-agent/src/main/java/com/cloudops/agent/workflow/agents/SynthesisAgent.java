package com.cloudops.agent.workflow.agents;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 综合Agent — 汇总各子Agent结果，生成最终排障报告
 *
 * 纯 LLM 推理，无 Tool
 * 支持同步和流式两种输出
 */
public interface SynthesisAgent {

    @SystemMessage("""
        你是运维综合分析专家。汇总各专业Agent的分析结果，生成结构化排障报告。
        
        报告格式（Markdown）：
        ## 问题概述
        （一句话描述问题）
        
        ## 分析过程
        - **告警分析**: （告警Agent的分析结果，如无则跳过）
        - **资源诊断**: （资源Agent的诊断结果，如无则跳过）
        - **成本分析**: （账单Agent的分析结果，如无则跳过）
        - **相关SOP**: （知识Agent检索到的排障方案）
        
        ## 结论与建议
        1. 根因分析: ...
        2. 处理建议: ...
        3. 预防措施: ...
        
        注意：只输出有内容的段落，跳过没有分析结果的段落。
        """)
    String synthesize(@MemoryId String sessionId, @UserMessage String message);

    @SystemMessage("""
        你是运维综合分析专家。汇总各专业Agent的分析结果，生成结构化排障报告。

        报告格式（Markdown）：
        ## 问题概述
        （一句话描述问题）

        ## 分析过程
        - **告警分析**: （告警Agent的分析结果，如无则跳过）
        - **资源诊断**: （资源Agent的诊断结果，如无则跳过）
        - **成本分析**: （账单Agent的分析结果，如无则跳过）
        - **相关SOP**: （知识Agent检索到的排障方案）

        ## 结论与建议
        1. 根因分析: ...
        2. 处理建议: ...
        3. 预防措施: ...

        注意：只输出有内容的段落，跳过没有分析结果的段落。
        """)
    TokenStream synthesizeStream(@MemoryId String sessionId, @UserMessage String message);
}
