package com.cloudops.tool;

import com.cloudops.entity.MockResourceRelation;
import com.cloudops.skill.ResourceRelationSkill;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资源拓扑查询 Tool — AI 适配层，包装 ResourceRelationSkill
 *
 * 排障时 Agent 调这个 Tool 看"这台机器关联了哪些资源"：
 *   ecs-001 挂了哪些云盘？绑了哪个EIP？挂载了NAS吗？
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceRelationTool extends AbstractTool {

    private final ResourceRelationSkill resourceRelationSkill;

    /**
     * 查某资源的所有关联资源（直接关联，1层，不递归）
     */
    @Tool("查询指定资源关联的所有资源列表，如云主机挂载的云盘、绑定的弹性IP、挂载的NAS等")
    public List<MockResourceRelation> queryRelation(@P("资源ID，如ecs-001") String resourceId) {
        return execute("queryRelation", () -> resourceRelationSkill.searchRelation(resourceId)).getData();
    }

    /**
     * 按关联类型过滤（只看挂载的盘 / 只看绑定的EIP）
     */
    @Tool("按关联类型查询资源关系，relationType可选：mount(挂载云盘)、eip(弹性IP)、nas(文件存储)、gpu_bind(GPU绑定)、share(共享)")
    public List<MockResourceRelation> queryRelationByType(
            @P("资源ID") String resourceId,
            @P("关联类型：mount/eip/nas/gpu_bind/share") String relationType
    ) {
        return execute("queryRelationByType", () -> resourceRelationSkill.searchByType(resourceId, relationType)).getData();
    }
}
