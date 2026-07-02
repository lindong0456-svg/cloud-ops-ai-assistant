package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockResourceRelation;
import com.cloudops.mapper.ResourceRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资源拓扑查询 Skill — 能力内核，无 AI 感知
 *
 * 对标联通 CMDB 的 resource_relation_config 设计：
 *   联通用 type=1/2/3 区分关联方式，这里用 mount/eip/nas/gpu_bind/share 更可读。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceRelationSkill {

    private final ResourceRelationMapper resourceRelationMapper;

    /**
     * 查某资源的所有关联资源（直接关联，1层，不递归）
     */
    public List<MockResourceRelation> searchRelation(String resourceId) {
        LambdaQueryWrapper<MockResourceRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceRelation::getResourceId, resourceId);
        return resourceRelationMapper.selectList(wrapper);
    }

    /**
     * 按关联类型过滤（只看挂载的盘 / 只看绑定的EIP）
     */
    public List<MockResourceRelation> searchByType(String resourceId, String relationType) {
        LambdaQueryWrapper<MockResourceRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceRelation::getResourceId, resourceId)
               .eq(MockResourceRelation::getRelationType, relationType);
        return resourceRelationMapper.selectList(wrapper);
    }
}
