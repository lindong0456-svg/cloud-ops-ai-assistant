package com.cloudops.mcp.tool;

import com.cloudops.entity.MockAlarm;
import com.cloudops.skill.AlarmSearchSkill;
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

public class AlarmSearchMcpTool {
    private static final ObjectMapper om;
    static {
        om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static SyncToolSpecification from(AlarmSearchSkill skill) {
        return SyncToolSpecification.builder()
            .tool(Tool.builder()
                .name("searchUnresolvedAlarms")
                .description("查询最近 N 条未处理告警")
                .inputSchema(new JsonSchema("object",
                    Map.of("limit", Map.of("type","integer","description","查询数量")),
                    null, null, null, null))
                .build())
            .callHandler((exchange, request) -> {
                try {
                    int limit = ((Number) request.arguments().getOrDefault("limit", 5)).intValue();
                    List<MockAlarm> alarms = skill.searchUnresolved(limit);
                    return new CallToolResult(List.of(new TextContent(om.writeValueAsString(alarms))), false);
                } catch (Exception e) {
                    return new CallToolResult(List.of(new TextContent("Error: "+e.getMessage())), true);
                }
            })
            .build();
    }
}
