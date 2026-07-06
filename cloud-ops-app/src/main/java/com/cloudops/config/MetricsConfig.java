package com.cloudops.config;

import com.cloudops.tool.AbstractTool;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Micrometer MeterRegistry 注入 AbstractTool 静态字段
 *
 * AbstractTool 不是 Spring Bean，无法直接依赖注入。
 * 通过这个配置类在应用启动时把 MeterRegistry 注入静态字段，
 * 模板方法 execute() 里就可以直接使用。
 */
@Configuration
@RequiredArgsConstructor
public class MetricsConfig {

    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        AbstractTool.meterRegistry = meterRegistry;
    }
}
