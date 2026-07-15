package com.cloudops.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.cloudops.security.interceptor.DataPermissionInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置 — 注册数据权限拦截器
 *
 * MybatisPlusInterceptor 是一个拦截器链，可以注册多个 InnerInterceptor:
 *   - DataPermissionInterceptor: 我们的数据权限拦截器
 *   - PaginationInnerInterceptor: 分页拦截器（如果需要分页可以加）
 *
 * 执行顺序: 按 addInnerInterceptor 的顺序执行
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 数据权限拦截器（自动追加 WHERE tenant_id = ?）
        interceptor.addInnerInterceptor(new DataPermissionInterceptor());
        return interceptor;
    }
}
