package com.cloudops.security.service;

import com.cloudops.security.dto.LoginRequest;
import com.cloudops.security.dto.LoginResponse;
import com.cloudops.security.entity.SysUser;
import com.cloudops.security.jwt.JwtPayload;
import com.cloudops.security.jwt.JwtUtil;
import com.cloudops.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 认证服务 — 登录、Token签发
 *
 * 登录流程:
 *   1. 根据 username 查 sys_user 表
 *   2. BCrypt 校验密码
 *   3. 查用户的角色列表和权限列表
 *   4. 签发 JWT Token
 *   5. 返回 Token + 用户信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 用户登录
     *
     * @param request 用户名+密码
     * @return LoginResponse 含Token和用户信息，登录失败抛异常
     */
    public LoginResponse login(LoginRequest request) {
        // 1. 查用户
        SysUser user = userMapper.selectByUsername(request.username());
        if (user == null) {
            log.warn("[登录] 用户不存在: {}", request.username());
            throw new RuntimeException("用户名或密码错误");
        }

        // 2. 校验密码（BCrypt: 明文 vs 密文）
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("[登录] 密码错误: {}", request.username());
            throw new RuntimeException("用户名或密码错误");
        }

        // 3. 查角色和权限
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getUserId());
        List<String> permissions = userMapper.selectPermissionCodesByUserId(user.getUserId());

        // 防御性处理：空值转空列表
        roles = roles != null ? roles : Collections.emptyList();
        permissions = permissions != null ? permissions : Collections.emptyList();

        log.info("[登录] 成功, userId={}, username={}, roles={}, permissions={}",
                user.getUserId(), user.getUsername(), roles, permissions);

        // 4. 构造 JWT 载荷
        JwtPayload payload = new JwtPayload(
                user.getUserId(),
                user.getUsername(),
                user.getTenantId(),
                user.getDeptId(),
                roles,
                permissions
        );

        // 5. 签发 Token
        String token = jwtUtil.generateToken(payload);

        // 6. 返回登录响应
        return new LoginResponse(
                token,
                user.getUserId(),
                user.getUsername(),
                user.getTenantId(),
                user.getDeptId(),
                roles,
                permissions
        );
    }
}
