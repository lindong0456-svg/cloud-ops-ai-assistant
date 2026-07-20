package com.cloudops.controller;

import com.cloudops.config.ModelDef;
import com.cloudops.config.ModelManager;
import com.cloudops.security.context.SecurityContext;
import com.cloudops.security.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模型管理接口
 *   GET  /api/agent/model         → 当前模型 + 可选模型列表（前端徽标 + 切换下拉）
 *   POST /api/agent/model/switch  → 切换激活模型（仅 SUPER_ADMIN / TENANT_ADMIN）
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ModelController {

    private final ModelManager modelManager;

    /** 当前模型信息 + 可选模型列表 */
    @GetMapping("/model")
    public Map<String, Object> current() {
        ModelDef active = modelManager.getActiveDef();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("active", active != null ? active.getKey() : null);
        resp.put("label", active != null ? active.getLabel() : null);
        resp.put("provider", active != null ? active.getProvider() : null);
        resp.put("models", modelManager.getModelDefs().stream()
                .map(m -> {
                    Map<String, String> e = new LinkedHashMap<>();
                    e.put("key", m.getKey());
                    e.put("label", m.getLabel());
                    return e;
                })
                .collect(Collectors.toList()));
        return resp;
    }

    /**
     * 切换模型 — 仅管理员
     * 切换后会话内的下一次对话自动使用新模型，done 事件会携带新模型名，
     * 前端据此弹“已切换模型”提示。
     */
    @PostMapping("/model/switch")
    public ResponseEntity<Map<String, Object>> switchModel(@RequestParam String key) {
        // 管理员门控：复用项目已有的角色体系，避免新增权限表
        UserContext ctx = SecurityContext.get();
        boolean admin = ctx != null && (ctx.isSuperAdmin()
                || (ctx.roles() != null && ctx.roles().contains("TENANT_ADMIN")));
        if (!admin) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限切换模型，仅管理员可操作"));
        }
        try {
            modelManager.switchTo(key);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        ModelDef active = modelManager.getActiveDef();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("active", active.getKey());
        resp.put("label", active.getLabel());
        return ResponseEntity.ok(resp);
    }
}
