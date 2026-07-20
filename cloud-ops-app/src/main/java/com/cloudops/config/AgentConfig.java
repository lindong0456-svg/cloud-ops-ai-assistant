package com.cloudops.config;

/**
 * 历史说明：
 *   原 AgentConfig 在此用 AiServices.builder 写死构建 OpsAssistant（DeepSeek）。
 *   模型构建已迁移到 {@link ModelManager}（配置驱动 + 运行时可切换）。
 *
 * 本类保留为空配置占位，避免删除后潜在引用报错；不再定义任何 Bean。
 */
@org.springframework.context.annotation.Configuration
public class AgentConfig {
}
