package com.cloudops.rag;

import com.cloudops.tool.ToolResult;
import com.cloudops.tool.annotation.RequiredPermission;
import com.cloudops.tool.AbstractTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识检索 Tool — AI 适配层，包装 KnowledgeRetrievalService
 *
 * 当 Agent 排障需要查 SOP 文档时调这个 Tool：
 *   用户："ecs-001 CPU 持续90%怎么办"
 *   Agent → queryAlarms(ecs-001) → queryLoad(ecs-001, 7)
 *   Agent → searchKnowledge("CPU持续高负载") ← 调这个
 *   Agent 综合告警+负载+SOP 给出处置方案
 *
 * 返回格式：每条结果格式化为 "[来源] 内容摘要"，方便 LLM 理解
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalTool extends AbstractTool {

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * 搜索运维知识库
     * 当用户问"怎么处理""有没有SOP""这种情况怎么办"时，AI 会调这个方法
     */
    @RequiredPermission("rag:read")
    @Tool("搜索运维知识库(SOP文档)，返回相关的处置方案和操作步骤。输入自然语言查询，如'CPU高怎么办''磁盘满怎么处理'")
    public ToolResult<String> searchKnowledge(@P("查询内容，自然语言描述问题") String query) {
        return execute("searchKnowledge", () -> {
            List<KnowledgeChunk> chunks = knowledgeRetrievalService.hybridSearch(query);
            if (chunks.isEmpty()) {
                return "未找到相关知识库文档，建议换个关键词或描述具体告警内容。";
            }
            // 格式化返回：每条结果 [来源] + 内容摘要（前500字）
            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(chunks.size()).append(" 条相关知识：\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                KnowledgeChunk chunk = chunks.get(i);
                sb.append("【").append(i + 1).append("】来源: ").append(chunk.getSource())
                  .append(" (综合分数: ").append(String.format("%.4f", chunk.getScore())).append(")\n");
                // 内容摘要：取前500字符，避免超长
                String content = chunk.getContent();
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                sb.append(content).append("\n\n");
            }
            return sb.toString();
        });
    }
}
