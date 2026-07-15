package com.cloudops.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具类 — Token 的签发、解析、验证
 *
 * 技术选型: jjwt 0.12.6
 *   - 0.12.x 使用新的 Builder API: Jwts.builder().subject().claim().signWith()
 *   - 旧版 0.11.x 的 setSubject()/setClaims() 已废弃
 *   - 签名算法: HS256（HMAC-SHA256），对称加密，Demo够用
 *     生产环境如需非对称签名可用 RS256
 *
 * 密钥来源:
 *   - 通过 application.yml 的 jwt.secret 配置
 *   - HS256 要求密钥至少 256 位（32字节）
 *   - Demo 用固定字符串，生产从环境变量注入
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret:cloud-ops-ai-assistant-secret-key-2026-must-be-at-least-32-bytes}")
    private String secret;

    @Value("${jwt.expiration:86400000}")  // 默认24小时（毫秒）
    private long expiration;

    /**
     * 获取签名密钥
     * 每次调用从配置字符串生成 SecretKey 对象
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 JWT Token
     *
     * @param payload 用户信息
     * @return JWT字符串（三段式: header.payload.signature）
     */
    public String generateToken(JwtPayload payload) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        String token = Jwts.builder()
                .subject(payload.userId())                    // 标准字段: subject = userId
                .claim("username", payload.username())         // 自定义 claim
                .claim("tenantId", payload.tenantId())
                .claim("deptId", payload.deptId())
                .claim("roles", payload.roles())
                .claim("permissions", payload.permissions())
                .issuedAt(now)                                  // 签发时间
                .expiration(expiryDate)                         // 过期时间
                .signWith(getSigningKey())                     // 签名
                .compact();                                     // 压缩为字符串

        log.info("[JWT] Token签发成功, userId={}, username={}, 过期时间={}",
                payload.userId(), payload.username(), expiryDate);
        return token;
    }

    /**
     * 解析 JWT Token，提取用户信息
     *
     * @param token JWT字符串
     * @return JwtPayload，解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    public JwtPayload parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())               // 验证签名
                    .build()
                    .parseSignedClaims(token)                 // 解析
                    .getPayload();                             // 获取载荷

            return new JwtPayload(
                    claims.getSubject(),                       // userId
                    claims.get("username", String.class),
                    claims.get("tenantId", String.class),
                    claims.get("deptId", String.class),
                    claims.get("roles", List.class),            // roles 列表
                    claims.get("permissions", List.class)      // permissions 列表
            );
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token已过期: {}", e.getMessage());
            return null;
        } catch (JwtException e) {
            log.warn("[JWT] Token解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证 Token 是否有效（未过期 + 签名正确）
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException e) {
            log.warn("[JWT] Token验证失败: {}", e.getMessage());
            return false;
        }
    }
}
