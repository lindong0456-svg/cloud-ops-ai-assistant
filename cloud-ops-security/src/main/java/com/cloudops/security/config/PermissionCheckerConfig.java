package com.cloudops.security.config;

import com.cloudops.security.context.RequestContextStore;
import com.cloudops.security.context.SecurityContext;
import com.cloudops.tool.RequestContextHolder;
import com.cloudops.tool.ToolPermissionChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 权限检查器配置 — 将 SecurityContext 适配为 ToolPermissionChecker 接口（三级回退策略）
*/
@Configuration
public class PermissionCheckerConfig {

    /**
     * 注册 ToolPermissionChecker Bean，供所有 AbstractTool 子类注入使用
 *
 * 三级权限查找（自顶向下，找到即放行）：
 *   Level 1: SecurityContext.hasPermission(code) — ThreadLocal，同线程/已传播线程
 *   Level 2: RequestContextStore.get(userId).hasPermission(code) — ThreadLocal userId 兜底
 *   Level 3: 遍历 RequestContextStore 所有活跃上下文 — 跨线程兜底（LangChain4j 工作线程）
 *
 * ★ 为什么需要 Level 3：
 *   LangChain4j 的工具执行在工作线程池上运行，SecurityContext（ThreadLocal）和
 *   RequestContextHolder（ThreadLocal）都无法传播到这些线程。
 *   RequestContextStore 是 ConcurrentHashMap，ChatController 在调 Agent 前写入，
 *   全线程可见。Level 3 遍历所有活跃上下文，确保工作线程也能完成权限校验。
     */
    @Bean
    public ToolPermissionChecker toolPermissionChecker() {
        return code -> {
            // Level 1: ThreadLocal（正常路径）
            if (SecurityContext.hasPermission(code)) {
                return true;
            }

            // Level 2: 通过 ThreadLocal userId 查找 RequestContextStore（同线程快速路径，保留兼容）
            String userId = RequestContextHolder.getCurrentUserId();
            if (userId != null) {
                var ctx = RequestContextStore.get(userId);
                if (ctx != null && ctx.hasPermission(code)) {
                    return true;
                }
            }

            // Level 3: ★ 遍历所有活跃上下文（跨线程兜底）
            //          LangChain4j 工作线程上 ThreadLocal 不可用，直接遍历 ConcurrentHashMap
            for (var ctx : com.cloudops.security.context.RequestContextStore.getAll()) {
                if (ctx.hasPermission(code)) {
                    return true;
                }
            }

            return false;
        };
    }
}
