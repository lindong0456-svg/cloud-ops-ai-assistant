package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockResourceRelation;
import com.cloudops.mapper.ResourceRelationMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ResourceRelationSkill {

    private final ResourceRelationMapper mapper;

    public ResourceRelationSkill(ResourceRelationMapper mapper) { this.mapper = mapper; }

    public List<MockResourceRelation> searchRelation(String resourceId) {
        LambdaQueryWrapper<MockResourceRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceRelation::getResourceId, resourceId);
        return mapper.selectList(wrapper);
    }

    public List<MockResourceRelation> searchByType(String resourceId, String relationType) {
        LambdaQueryWrapper<MockResourceRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceRelation::getResourceId, resourceId)
               .eq(MockResourceRelation::getRelationType, relationType);
        return mapper.selectList(wrapper);
    }
}
