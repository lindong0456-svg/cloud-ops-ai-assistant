package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockAlarm;
import com.cloudops.mapper.AlarmMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警检索 Skill — 能力内核，无 AI 感知
 *
 * 设计原则：
 *   - 无 @Tool 注解（不绑定 LangChain4j）
 *   - 无 AbstractTool 依赖（不绑定 Agent 框架）
 *   - 可被 Tool / MCP / REST / 定时任务 复用
 *
 * 被 AlarmQueryTool（Agent 适配）和未来 AlarmMcpResource（MCP 适配）复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmSearchSkill {

    private final AlarmMapper alarmMapper;

    /**
     * 查询最近 N 条未处理告警
     */
    public List<MockAlarm> searchUnresolved(int limit) {
        LambdaQueryWrapper<MockAlarm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockAlarm::getStatus, "unresolved")
                .orderByDesc(MockAlarm::getTriggerTime)
                .last("LIMIT " + limit);
        return alarmMapper.selectList(wrapper);
    }

    /**
     * 按资源 ID 查询告警
     */
    public List<MockAlarm> searchByResource(String resourceId) {
        LambdaQueryWrapper<MockAlarm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockAlarm::getResourceId, resourceId)
                .orderByDesc(MockAlarm::getTriggerTime);
        return alarmMapper.selectList(wrapper);
    }
}
