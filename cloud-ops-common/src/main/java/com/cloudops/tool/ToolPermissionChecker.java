package com.cloudops.tool;

/**
 * Tool 权限检查接口 — 由 security 模块实现，避免循环依赖
 *
 * AbstractTool 通过此接口校验 @RequiredPermission 声明的权限，
 * 实现类读取 SecurityContext（ThreadLocal）中的当前用户权限。
 *
 * Spring 自动注入：security 模块注册 Bean，common 模块的 AbstractTool 消费。
 */
@FunctionalInterface
public interface ToolPermissionChecker {

    /**
     * 当前用户是否拥有指定权限
     *
     * @param permissionCode 权限编码，如 "rag:read"、"billing:read"
     * @return 有权限返回 true
     */
    boolean hasPermission(String permissionCode);
}
