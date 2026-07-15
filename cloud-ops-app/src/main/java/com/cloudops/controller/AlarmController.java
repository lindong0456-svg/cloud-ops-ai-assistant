package com.cloudops.controller;

import com.cloudops.entity.MockAlarm;
import com.cloudops.skill.AlarmSearchSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 告警列表接口 — T28 一键排障
 *
 * 前端页面加载时拉取未处理告警列表，展示在左栏。
 * 用户点击某条告警的"排障"按钮，前端自动构造排障消息发给 Agent。
 *
 * 复用 AlarmSearchSkill（不经过 Agent），直接查数据库返回。
 */
@Slf4j
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmSearchSkill alarmSearchSkill;

    /**
     * 查询未处理告警列表
     * 调用：GET /api/alarms?limit=10
     */
    @GetMapping
    @PreAuthorize("hasAuthority('alarm:read')")
    public Map<String, Object> listAlarms(@RequestParam(defaultValue = "10") int limit) {
        log.info("[Alarm] 查询未处理告警, limit={}", limit);
        List<MockAlarm> alarms = alarmSearchSkill.searchUnresolved(limit);
        log.info("[Alarm] 查询完成, 返回 {} 条告警", alarms.size());
        return Map.of(
                "status", "success",
                "count", alarms.size(),
                "alarms", alarms
        );
    }
}
