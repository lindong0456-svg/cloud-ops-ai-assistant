package com.cloudops.tool;

/**
 * 请求级上下文持有者 — 存储当前请求的 userId
 *
 * 问题背景：
 *   ToolPermissionChecker 接口只有 hasPermission(String code)，没有 userId 参数。
 *   但回退到 RequestContextStore 时需要 userId 来查找用户上下文。
 *
 * 解决方案：
 *   用 ThreadLocal 持有当前请求的 userId。
 *   ChatController 在调用 Agent 前设置，finally 中清除。
 *   权限检查器在 SecurityContext 取不到时，用此 userId 回查 RequestContextStore。
 *
 * 生命周期：ChatController 设置 → Tool 执行 → ChatController finally 清除
 */
public class RequestContextHolder {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    /**
     * 设置当前请求的 userId（ChatController 调 Agent 前调用）
     */
    public static void setCurrentUserId(String userId) {
        if (userId != null) {
            CURRENT_USER_ID.set(userId);
        }
    }

    /**
     * 获取当前请求的 userId
     */
    public static String getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 清除当前请求的 userId（ChatController finally 中调用）
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
