package com.cloudops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口
 * 作用：验证项目能启动、依赖能加载
 * 先调这个接口确认服务活着
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "project", "cloud-ops-ai-assistant",
                "version", "0.0.1-SNAPSHOT"
        );
    }
}
