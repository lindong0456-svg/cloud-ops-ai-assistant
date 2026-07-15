package com.cloudops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 云运维智能助手 - 启动类
 *
 * @MapperScan：扫描 domain 模块的 Mapper 接口
 * @SpringBootApplication：默认扫描 com.cloudops 及其子包
 *   - com.cloudops.config        → AgentConfig, ChatMemoryConfig
 *   - com.cloudops.controller    → ChatController, RagController, HealthController
 *   - com.cloudops.agent         → OpsAssistant 接口（@AiService 代理在 AgentConfig 构建）
 *   - com.cloudops.guardrail     → InputGuardrail, GuardrailConfig
 *   - com.cloudops.registry      → ToolRegistry, ToolRegistryConfig
 *   - com.cloudops.rag           → DocumentIngestService, KnowledgeRetrievalService, KnowledgeRetrievalTool
 *   - com.cloudops.skill         → 4 个 Skill
 *   - com.cloudops.tool          → 4 个 Tool
 */
@SpringBootApplication
@MapperScan({"com.cloudops.mapper", "com.cloudops.security.mapper"})
public class CloudOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudOpsApplication.class, args);
    }
}
