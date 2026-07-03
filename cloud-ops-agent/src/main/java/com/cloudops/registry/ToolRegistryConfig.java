package com.cloudops.registry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool 注册配置属性绑定
 *
 * 从 tools-config.yml 加载，控制 Agent 启用哪些 Tool。
 *
 * 对标联通 product_config 配置驱动设计：
 *   联通用 YAML 定义产品规格，代码按配置动态加载产品能力；
 *   这里用 YAML 定义启用的 Tool，Agent 按配置动态注册能力。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tools")
public class ToolRegistryConfig {

    /** 是否启用配置驱动模式（false=全量注册所有 Tool Bean） */
    private boolean configDriven = true;

    /** 启用的 Tool Bean 名称列表 */
    private List<String> enabled = new ArrayList<>();
}
