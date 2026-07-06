package com.cloudops.mcp.tool;

import com.cloudops.entity.MockBillingStream;
import com.cloudops.skill.BillingQuerySkill;
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

public class BillingQueryMcpTool {
    private static final ObjectMapper om;
    static {
        om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static SyncToolSpecification from(BillingQuerySkill skill) {
        return SyncToolSpecification.builder()
            .tool(Tool.builder()
                .name("queryBillingByTenant")
                .description("按租户ID和账期查询账单流水")
                .inputSchema(new JsonSchema("object",
                    Map.of(
                        "tenantId", Map.of("type","string","description","租户ID"),
                        "billingPeriod", Map.of("type","integer","description","账期，如202607")
                    ), null, null, null, null))
                .build())
            .callHandler((exchange, request) -> {
                try {
                    String tenantId = (String) request.arguments().get("tenantId");
                    int period = ((Number) request.arguments().get("billingPeriod")).intValue();
                    List<MockBillingStream> bills = skill.searchByTenant(tenantId, period);
                    return new CallToolResult(List.of(new TextContent(om.writeValueAsString(bills))), false);
                } catch (Exception e) {
                    return new CallToolResult(List.of(new TextContent("Error: "+e.getMessage())), true);
                }
            })
            .build();
    }
}
