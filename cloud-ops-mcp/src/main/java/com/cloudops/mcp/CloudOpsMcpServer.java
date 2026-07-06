package com.cloudops.mcp;

import com.cloudops.mapper.AlarmMapper;
import com.cloudops.mapper.BillingStreamMapper;
import com.cloudops.mapper.ResourceLoadMapper;
import com.cloudops.mcp.tool.AlarmSearchMcpTool;
import com.cloudops.mcp.tool.BillingQueryMcpTool;
import com.cloudops.mcp.tool.ResourceLoadMcpTool;
import com.cloudops.skill.AlarmSearchSkill;
import com.cloudops.skill.BillingQuerySkill;
import com.cloudops.skill.ResourceLoadSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@MapperScan("com.cloudops.mapper")
public class CloudOpsMcpServer {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(CloudOpsMcpServer.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);

        AlarmSearchSkill alarmSkill = new AlarmSearchSkill(ctx.getBean(AlarmMapper.class));
        ResourceLoadSkill loadSkill = new ResourceLoadSkill(ctx.getBean(ResourceLoadMapper.class));
        BillingQuerySkill billingSkill = new BillingQuerySkill(ctx.getBean(BillingStreamMapper.class));

        // MCP JSON Mapper (Jackson2 适配器，注册 JavaTimeModule 支持 LocalDateTime)
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(om);

        // 构建 MCP Server（Sync 模式，stdio 传输）
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(jsonMapper))
                .serverInfo("云运维智能助手", "1.0.0")
                .capabilities(new ServerCapabilities(null, null, null, null, null, new ServerCapabilities.ToolCapabilities(false)))
                .build();

        server.addTool(AlarmSearchMcpTool.from(alarmSkill));
        server.addTool(ResourceLoadMcpTool.from(loadSkill));
        server.addTool(BillingQueryMcpTool.from(billingSkill));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            ctx.close();
        }));

        System.out.println("[MCP] Cloud Ops MCP Server ready");
        // Server 通过 StdioServerTransport 自动启动，main 线程等待 JVM 退出
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
    }
}
