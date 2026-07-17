package com.cloudops.security.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级安全上下文存储 — 跨线程安全
 *
 * 问题背景：
 *   SecurityContext 基于 ThreadLocal，在 HTTP 线程由 JwtAuthenticationFilter 设置。
 *   但 LangChain4j 的 TokenStream / 工具执行可能在线程池（lTaskExecutor）上运行，
 *   ThreadLocal 不会自动传播，导致 DataPermissionInterceptor 和 Tool 权限校验
 *   拿不到用户上下文 → 要么跳过权限过滤（不安全），要么误拦截。
 *
 * 解决方案：
 *   在调用 Agent 前，将当前用户上下文存入此并发 Map（key = userId）。
 *   Tool 执行 / MyBatis 拦截器读取时：先查 ThreadLocal（HTTP 线程直接命中），
 *   再查此 Map（异步线程兜底）。Agent 调用完成后清除。
 *
 * 生命周期：ChatController.captureContext(userId) → Agent 执行 → releaseContext(userId)
 */
public class RequestContextStore {

    private static final Map<String, UserContext> STORE = new ConcurrentHashMap<>();

    /**
     * 存储用户上下文（ChatController 在调 Agent 前调用）
     */
    public static void put(String userId, UserContext ctx) {
        if (userId != null && ctx != null) {
            STORE.put(userId, ctx);
        }
    }

    /**
     * 获取用户上下文（优先 ThreadLocal，回退到请求级存储）
     * 供 DataPermissionInterceptor / ToolPermissionChecker 使用
     */
    public static UserContext get(String userId) {
        // 1. 先查 ThreadLocal（同线程场景）
        UserContext ctx = SecurityContext.get();
        if (ctx != null) return ctx;

        // 2. 回查请求级存储（跨线程/异步场景）
        if (userId != null) {
            return STORE.get(userId);
        }
        return null;
    }

    /**
     * 清除用户上下文（ChatController 在 Agent 执行完后调用）
     */
    public static void remove(String userId) {
        if (userId != null) {
            STORE.remove(userId);
        }
    }

    /**
     * 仅用于诊断：查看当前存储的所有 key
     */
    public static int size() {
        return STORE.size();
    }

    /**
     * 获取所有活跃的 UserContext（供跨线程权限回退使用）
     *
     * PermissionCheckerConfig Level 2 回退时调用此方法遍历所有活跃上下文。
     * 解决了 LangChain4j 工作线程上 ThreadLocal（SecurityContext / RequestContextHolder）
     * 传播失败导致工具权限校验全部被拒的问题。
     */
    public static java.util.Collection<UserContext> getAll() {
        return STORE.values();
    }
}
