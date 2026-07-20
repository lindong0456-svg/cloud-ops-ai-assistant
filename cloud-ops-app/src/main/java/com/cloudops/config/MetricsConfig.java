package com.cloudops.config;

import com.cloudops.security.context.RequestContextStore;
import com.cloudops.security.context.UserContext;
import com.cloudops.tool.AbstractTool;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Micrometer MeterRegistry + userIdResolver 注入 AbstractTool 静态字段
 *
 * AbstractTool 不是 Spring Bean，无法直接依赖注入。
 * 通过这个配置类在应用启动时把依赖注入静态字段，
 * 模板方法 execute() 里就可以直接使用。
 */
@Configuration
@RequiredArgsConstructor
public class MetricsConfig {

    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        AbstractTool.meterRegistry = meterRegistry;
        // ★ 跨线程 userId 解析器：工具在线程池执行时 ThreadLocal 不可用，
        //   回退到 RequestContextStore（ConcurrentHashMap）取活跃上下文
        AbstractTool.setUserIdResolver(() -> {
            for (UserContext ctx : RequestContextStore.getAll()) {
                return ctx.userId();
            }
            return null;
        });
    }
}
