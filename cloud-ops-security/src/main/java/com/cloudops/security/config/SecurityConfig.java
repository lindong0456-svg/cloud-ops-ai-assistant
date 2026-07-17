package com.cloudops.security.config;

import com.cloudops.security.handler.RestAccessDeniedHandler;
import com.cloudops.security.handler.RestAuthenticationEntryPoint;
import com.cloudops.security.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置 — 安全体系的核心
 *
 * @EnableMethodSecurity: 启用方法级权限注解 @PreAuthorize
 *   不加这个注解，Controller 上的 @PreAuthorize 不会生效
 *
 * @MapperScan: 扫描 security 模块的 Mapper
 *   cloud-ops-app 的启动类可能只扫描了 com.cloudops 包，
 *   但 security 模块的 Mapper 在 com.cloudops.security.mapper 包下
 *   显式扫描确保 MyBatis-Plus 能找到这些 Mapper
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@MapperScan(basePackages = "com.cloudops.security.mapper")
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    private final RestAccessDeniedHandler accessDeniedHandler;

    /**
     * 安全过滤器链 — 替代旧版 WebSecurityConfigurerAdapter
     *
     * 关键配置:
     *   1. CSRF 禁用: JWT 无状态认证不需要 CSRF 保护
     *   2. Session 无状态: 不创建 HttpSession，每次请求独立认证
     *   3. 路径授权: 登录接口和监控端点放行，其余需认证
     *   4. 过滤器顺序: JWT过滤器在 UsernamePasswordAuthenticationFilter 之前
     *   5. CORS: 允许前端跨域
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {


        http
                // CSRF 禁用（JWT 是无状态的，不需要 CSRF Token）
                .csrf(csrf -> csrf.disable())

                // CORS 配置
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 无状态 Session（不创建 HttpSession）
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 路径授权
                .authorizeHttpRequests(auth -> auth
                        // 公开接口：登录、健康检查、Prometheus指标
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/sop/**").permitAll()  // SOP查看暂时放行
                        .requestMatchers("/error").permitAll()       // Spring Boot 错误转发路径（防止异常→/error→401 误判）
                        // 其余接口需要认证
                        .anyRequest().authenticated()
                )

                // 异常处理：未认证返回401 JSON，权限不足返回403 JSON
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                // 注册 JWT 过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器 — BCrypt
     *
     * BCrypt 特点:
     *   - 每次加密生成不同密文（内含随机 salt）
     *   - 验证时自动提取 salt 进行比对
     *   - 计算成本可调（默认 strength=10），防止暴力破解
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS 配置 — 允许前端跨域访问
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:3001", "http://localhost:3002", "http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);  // 预检请求缓存1小时

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
