package com.cloudops.security.jwt;

import java.util.List;

/**
 * JWT 载荷 — Token 中携带的用户信息
 *
 * 与 UserContext 字段一致，但职责不同：
 *   - JwtPayload: Token 序列化/反序列化时的中间对象
 *   - UserContext: 请求处理时的运行时对象
 *
 * JWT 结构（三段式，Base64编码）:
 *   Header.Payload.Signature
 *   eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOiJ1c2VyLTAwMSJ9.signature
 *
 * Payload 内容（JSON）:
 * {
 *   "sub": "user-001",           ← subject，标准JWT字段
 *   "username": "ops_eng",
 *   "tenantId": "tenant-001",
 *   "deptId": "dept-prod-ops",
 *   "roles": ["OPS_ENGINEER"],
 *   "permissions": ["alarm:read","agent:chat"],
 *   "iat": 1718000000,           ← 签发时间
 *   "exp": 1718086400            ← 过期时间（24小时后）
 * }
 */
public record JwtPayload(
        String userId,
        String username,
        String tenantId,
        String deptId,
        List<String> roles,
        List<String> permissions
) {}
