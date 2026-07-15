package com.cloudops.security.jwt;

import com.cloudops.security.context.SecurityContext;
import com.cloudops.security.context.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器 — 每个请求执行一次
 *
 * 继承 OncePerRequestFilter 而不是 GenericFilterBean：
 *   OncePerRequestFilter 保证每个请求只执行一次（即使有 forward/include）
 *
 * 执行位置: 在 Spring Security 的 UsernamePasswordAuthenticationFilter 之前
 *           （在 SecurityConfig 中配置 addFilterBefore）
 *
 * 双写策略:
 *   - SecurityContextHolder: 写入 Spring Security 的 Authentication（给 @PreAuthorize 用）
 *   - SecurityContext (ThreadLocal): 写入我们的 UserContext（给业务代码用）
 *   两套并存，各取所需
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. 从 Header 提取 Token
            String token = extractToken(request);

            if (StringUtils.hasText(token)) {
                // 2. 解析 Token
                JwtPayload payload = jwtUtil.parseToken(token);

                if (payload != null) {
                    // 3. 构造 UserContext（我们的上下文对象）
                    UserContext userContext = new UserContext(
                            payload.userId(),
                            payload.username(),
                            payload.tenantId(),
                            payload.deptId(),
                            payload.roles(),
                            payload.permissions()
                    );

                    // 4. 写入 ThreadLocal（业务代码用）
                    SecurityContext.set(userContext);

                    // 5. 写入 Spring Security 的 SecurityContextHolder（@PreAuthorize 用）
                    //    将权限列表转为 SimpleGrantedAuthority
                    List<SimpleGrantedAuthority> authorities = payload.permissions().stream()
                            .map(SimpleGrantedAuthority::new)
                            .toList();
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(payload.username(), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("[JWT] 认证成功, userId={}, username={}, tenantId={}",
                            payload.userId(), payload.username(), payload.tenantId());
                }
            }

            // 6. 继续过滤器链（到达 Controller）
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.error("[JWT] 认证过滤器异常: {}", e.getMessage());
            // 不向上抛异常，让请求继续走 Security 链（未认证 → 403）
            // 但如果 doFilter 内部抛异常，需要重新抛出让 Tomcat 处理
            throw e;
        } finally {
            // 7. 请求处理完成（无论成功/异常），清理 ThreadLocal 防止内存泄漏
            //    finally 在 filterChain.doFilter() 之后执行，此时 Controller 已处理完毕
            SecurityContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 从 Authorization Header 提取 Token
     * 格式: "Bearer eyJhbGciOiJIUzI1NiJ9..."
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);  // 去掉 "Bearer " 前缀
        }
        log.debug("[JWT] 未找到 Token");
        return null;
    }
}
