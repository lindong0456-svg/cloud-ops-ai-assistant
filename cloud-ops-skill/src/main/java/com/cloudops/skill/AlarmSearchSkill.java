package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockAlarm;
import com.cloudops.mapper.AlarmMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

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

    public List<MockAlarm> searchUnresolved(int limit) {
        LambdaQueryWrapper<MockAlarm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockAlarm::getStatus, "unresolved")
                .orderByDesc(MockAlarm::getTriggerTime)
                .last("LIMIT " + limit);
        return mapper.selectList(wrapper);
    }

    public List<MockAlarm> searchByResource(String resourceId) {
        LambdaQueryWrapper<MockAlarm> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockAlarm::getResourceId, resourceId)
                .orderByDesc(MockAlarm::getTriggerTime);
        return mapper.selectList(wrapper);
    }
}
