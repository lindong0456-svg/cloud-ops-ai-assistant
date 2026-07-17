package com.cloudops.security.dto;

/**
 * 权限信息 — 编码 + 中文名称
 *
 * 前端 UserPanel 权限标签直接显示 name（如"告警查看"、"知识检索"）
 */
public record PermissionInfo(
        String code,   // 权限编码: "alarm:read"
        String name    // 权限名称: "告警查看"
) {}
