package com.cloudops.tool;

import com.cloudops.entity.MockResourceLoad;
import com.cloudops.skill.ResourceLoadSkill;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资源负载查询 Tool — AI 适配层，包装 ResourceLoadSkill
 *
 * 排障时 Agent 调这个 Tool 看"这台机器最近7天 CPU/内存多少"：
 *   ecs-001 CPU均值85峰值98 → 判断持续瓶颈
 *   ebm-001 内存7天从78%涨到93% → 判断内存泄漏
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceLoadTool extends AbstractTool {

    private final ResourceLoadSkill resourceLoadSkill;

    /**
     * 查某资源最近N天的负载（CPU/内存均值+峰值）
     */
    @Tool("查询指定资源最近N天的CPU和内存负载，返回每天的均值和峰值利用率")
    public List<MockResourceLoad> queryLoad(
            @P("资源ID，如ecs-001") String resourceId,
            @P("查询天数，如7") int days
    ) {
        return execute("queryLoad", () -> resourceLoadSkill.searchLoad(resourceId, days)).getData();
    }

    /**
     * 查某资源最新一天的负载快照
     */
    @Tool("查询指定资源最新一天的CPU和内存负载快照")
    public MockResourceLoad queryLatestLoad(@P("资源ID，如ecs-001") String resourceId) {
        return execute("queryLatestLoad", () -> resourceLoadSkill.searchLatestLoad(resourceId)).getData();
    }
}
