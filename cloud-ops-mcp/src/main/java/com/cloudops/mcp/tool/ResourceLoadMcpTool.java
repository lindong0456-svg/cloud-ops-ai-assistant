package com.cloudops.mcp.tool;

import com.cloudops.skill.ResourceLoadSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

public class ResourceLoadMcpTool {
    private static final ObjectMapper om;
    static {
        om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static SyncToolSpecification from(ResourceLoadSkill skill) {
        return SyncToolSpecification.builder()
            .tool(Tool.builder()
                .name("queryResourceLoad")
                .description("查询资源最近N天的CPU和内存负载")
                .inputSchema(new JsonSchema("object",
                    Map.of(
                        "resourceId", Map.of("type","string","description","资源ID"),
                        "days", Map.of("type","integer","description","查询天数")
                    ), null, null, null, null))
                .build())
            .callHandler((exchange, request) -> {
                try {
                    String resourceId = (String) request.arguments().get("resourceId");
                    int days = ((Number) request.arguments().getOrDefault("days", 7)).intValue();
                    return new CallToolResult(List.of(
                        new TextContent(om.writeValueAsString(skill.searchLoad(resourceId, days)))), false);
                } catch (Exception e) {
                    return new CallToolResult(List.of(new TextContent("Error: "+e.getMessage())), true);
                }
            })
            .build();
    }
}
