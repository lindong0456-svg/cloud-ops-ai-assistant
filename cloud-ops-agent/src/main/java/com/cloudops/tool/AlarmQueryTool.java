package com.cloudops.tool;

import com.cloudops.entity.MockAlarm;
import com.cloudops.skill.AlarmSearchSkill;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警查询 Tool — AI 适配层，包装 AlarmSearchSkill
 *
 * 职责：只做 @Tool 注解 + 日志计时（AbstractTool 模板），业务逻辑委托给 Skill。
 * 这样 MCP Server / REST 接口可以直接复用 AlarmSearchSkill，不必经过 Agent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmQueryTool extends AbstractTool {

    private final AlarmSearchSkill alarmSearchSkill;

    /**
     * 查询最近未处理告警
     * 当用户说"有什么告警""ecs-001怎么了"时，AI 会调这个方法
     */
    @Tool("查询最近N条未处理告警，返回告警ID、资源ID、资源类型、严重等级、告警内容、触发时间")
    public List<MockAlarm> queryAlarms(@P("查询数量，默认5") int limit) {
        return execute("queryAlarms", () -> alarmSearchSkill.searchUnresolved(limit)).getData();
    }

    /**
     * 按资源ID查询告警
     * 当 Agent 排查某台机器时，先调这个看有没有告警
     */
    @Tool("按资源ID查询该资源的所有告警，返回告警列表")
    public List<MockAlarm> queryAlarmsByResource(@P("资源ID，如ecs-001") String resourceId) {
        return execute("queryAlarmsByResource", () -> alarmSearchSkill.searchByResource(resourceId)).getData();
    }
}
