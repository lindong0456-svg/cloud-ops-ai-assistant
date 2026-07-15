package com.cloudops.controller;

import com.cloudops.security.dto.LoginRequest;
import com.cloudops.security.dto.LoginResponse;
import com.cloudops.security.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证 Controller — 登录接口
 *
 * 路径: /api/auth/login
 * 方法: POST
 * 权限: 公开（SecurityConfig 中 permitAll）
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录
     *
     * 请求体: {"username": "ops_eng", "password": "admin123"}
     * 响应体: {"token": "eyJ...", "userId": "user-ops", "username": "ops_eng", ...}
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            LoginResponse response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            // 登录失败返回 401
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }
}
