package com.cloudops.security.dto;

import java.util.List;

/**
 * 登录响应 — Token + 用户信息 + 权限列表（含中文）
 *
 * permissions 字段从 List<String>(code) 升级为 List<PermissionInfo>(code+name)，
 * 前端 UserPanel 直接显示中文权限名称。
 */
public record LoginResponse(
        String token,
        String userId,
        String username,
        String tenantId,
        String deptId,
        List<String> roles,
        List<PermissionInfo> permissions
) {}
