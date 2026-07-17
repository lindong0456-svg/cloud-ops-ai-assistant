package com.cloudops.security.context;

/**
 * 安全上下文 — 基于 ThreadLocal 的请求级用户信息持有器
 *
 * 生命周期：
 *   1. JwtAuthenticationFilter 解析 Token → 构造 UserContext
 *   2. SecurityContext.set(userContext)  ← 写入 ThreadLocal
 *   3. Controller / Service / Interceptor 随时 SecurityContext.get()  ← 读取
 *   4. 请求结束 → SecurityContext.clear()  ← 清理，防止内存泄漏
 *
 * 为什么不直接用 Spring Security 的 SecurityContextHolder？
 *   - SecurityContextHolder 存的是 Authentication 对象，取租户ID需要层层转换
 *   - 我们的 UserContext 直接持有 tenantId/deptId，更方便业务代码使用
 *   - 两者并存：SecurityContextHolder 给 Spring Security 框架用，SecurityContext 给业务代码用
 */
public class SecurityContext {

    private static final ThreadLocal<UserContext> CONTEXT = new ThreadLocal<>();

    /** 写入当前线程的用户上下文（JWT过滤器调用） */
    public static void set(UserContext context) {
        CONTEXT.set(context);
    }

    /** 获取当前线程的用户上下文（业务代码调用） */
    public static UserContext get() {
        return CONTEXT.get();
    }

    /** 清除当前线程的用户上下文（过滤器 finally 块调用，防止内存泄漏） */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 获取当前上下文快照（用于跨线程传播）
     * 在 HTTP 线程调用，拿到后在异步线程 set 回去
     */
    public static UserContext capture() {
        return CONTEXT.get();
    }

    /**
     * 将用户上下文传播到当前线程（通常在异步线程中调用）
     * 配合 capture() 使用：HTTP 线程 capture → 异程线程 restore
     */
    public static void restore(UserContext ctx) {
        if (ctx != null) {
            CONTEXT.set(ctx);
        }
    }

    /**
     * 包装 Runnable，自动传播 SecurityContext 到目标线程。
     * 用法：executor.execute(SecurityContext.wrap(runnable))
     */
    public static Runnable wrap(Runnable task) {
        UserContext ctx = CONTEXT.get();
        return () -> {
            UserContext previous = CONTEXT.get();
            try {
                if (ctx != null) CONTEXT.set(ctx);
                task.run();
            } finally {
                if (previous != null) {
                    CONTEXT.set(previous);
                } else {
                    CONTEXT.remove();
                }
            }
        };
    }

    // ===== 以下为快捷方法，避免到处写 SecurityContext.get().xxx() =====

    public static String getTenantId() {
        UserContext ctx = get();
        return ctx != null ? ctx.tenantId() : null;
    }

    public static String getDeptId() {
        UserContext ctx = get();
        return ctx != null ? ctx.deptId() : null;
    }

    public static String getUserId() {
        UserContext ctx = get();
        return ctx != null ? ctx.userId() : null;
    }

    public static boolean hasPermission(String code) {
        UserContext ctx = get();
        return ctx != null && ctx.hasPermission(code);
    }

    public static boolean isSuperAdmin() {
        UserContext ctx = get();
        return ctx != null && ctx.isSuperAdmin();
    }
}
