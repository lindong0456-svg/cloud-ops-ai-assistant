package com.cloudops.security.context;

import java.util.List;

/**
 * 用户安全上下文 — 一次请求中的用户身份信息
 *
 * 这个对象在 JWT 过滤器中创建，存入 ThreadLocal，
 * 供 Controller、Service、MyBatis 拦截器、Tool 权限切面随时读取
 *
 * 用 record 而不是 class：
 *   - 不可变，ThreadLocal 中不会有并发修改问题
 *   - Java 21 record 序列化性能好
 *   - 代码简洁，一行定义完所有字段
 */
public record UserContext(
        String userId,           // 用户编码: user-001
        String username,         // 登录名: ops_eng
        String tenantId,         // 租户编码: tenant-001
        String deptId,           // 部门编码: dept-prod-ops
        List<String> roles,      // 角色列表: ["OPS_ENGINEER"]
        List<String> permissions // 权限列表: ["alarm:read", "agent:chat", ...]
) {
    /**
     * 快捷方法：是否拥有某权限
     * 供 Tool 权限切面调用: SecurityContext.get().hasPermission("alarm:read")
     */
    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }

    /**
     * 快捷方法：是否是超级管理员
     * 数据权限拦截器用它判断是否跳过过滤
     */
    public boolean isSuperAdmin() {
        return roles != null && roles.contains("SUPER_ADMIN");
    }
}
