package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockAlarm;
import com.cloudops.mapper.AlarmMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 告警检索 Skill — 能力内核，无 AI 感知（纯 POJO，零框架依赖）
 *
 * 构造器注入 Mapper，MCP 端直接 new，Spring 端靠 @Bean。
 */
@Slf4j
public class AlarmSearchSkill {

    private final AlarmMapper mapper;

    public AlarmSearchSkill(AlarmMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 严重级别优先级映射（数值越小越靠前）
     * CRITICAL=0 > WARNING=1 > INFO=2
     */
    private static final Map<String, Integer> SEVERITY_ORDER = Map.of(
            "CRITICAL", 0,
            "WARNING", 1,
            "INFO", 2
    );

    public List<MockAlarm> searchUnresolved(int limit) {
        // 1. 先查未处理告警（按触发时间倒序取足够多的数据）
        LambdaQueryWrapper<MockAlarm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockAlarm::getStatus, "unresolved")
                .orderByDesc(MockAlarm::getTriggerTime);
        List<MockAlarm> all = mapper.selectList(wrapper);

        // 2. 按严重级别优先排序：CRITICAL → WARNING → INFO，同级别按时间倒序
        all.sort((a, b) -> {
            int pa = SEVERITY_ORDER.getOrDefault(a.getSeverity().toUpperCase(), 99);
            int pb = SEVERITY_ORDER.getOrDefault(b.getSeverity().toUpperCase(), 99);
            if (pa != pb) return pa - pb;
            return b.getTriggerTime().compareTo(a.getTriggerTime());
        });

        // 3. 截断到 limit
        return all.stream().limit(limit).toList();
    }

    public List<MockAlarm> searchByResource(String resourceId) {
        LambdaQueryWrapper<MockAlarm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockAlarm::getResourceId, resourceId)
                .orderByDesc(MockAlarm::getTriggerTime);
        return mapper.selectList(wrapper);
    }
}
