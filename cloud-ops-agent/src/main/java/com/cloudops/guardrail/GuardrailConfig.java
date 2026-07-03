package com.cloudops.guardrail;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * InputGuardrail 配置属性绑定
 *
 * 从 guardrail-config.yml 加载配置，Spring Boot 自动绑定到这个类。
 * 用 @ConfigurationProperties 而不是 @Value，因为字段多且嵌套。
 *
 * 对标联通的 YAML 配置驱动设计：
 *   联通 product_config 用 YAML 定义产品规格，代码按配置动态加载；
 *   这里用 YAML 定义敏感词，新增敏感词不用改代码，改配置重启即可。
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "guardrail")
public class GuardrailConfig {

    /** 是否启用护轨 */
    private boolean enabled = true;

    /** 敏感关键词列表 */
    private List<String> blockedKeywords = new ArrayList<>();

    /** 拦截提示信息 */
    private String blockedMessage;

    /** 白名单关键词（命中敏感词但含白名单词时放行） */
    private List<String> whitelistKeywords = new ArrayList<>();

    @PostConstruct
    public void init() {
        log.info("[Guardrail] 护轨配置加载完成: enabled={}, 敏感词{}个, 白名单{}个",
                enabled, blockedKeywords.size(), whitelistKeywords.size());
    }
}
