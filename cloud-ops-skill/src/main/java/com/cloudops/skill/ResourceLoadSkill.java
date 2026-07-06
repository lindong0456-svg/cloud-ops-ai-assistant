package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockResourceLoad;
import com.cloudops.mapper.ResourceLoadMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ResourceLoadSkill {

    private final ResourceLoadMapper mapper;

    public ResourceLoadSkill(ResourceLoadMapper mapper) { this.mapper = mapper; }

    public List<MockResourceLoad> searchLoad(String resourceId, int days) {
        LambdaQueryWrapper<MockResourceLoad> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceLoad::getResourceId, resourceId)
               .orderByDesc(MockResourceLoad::getBeginTime)
               .last("LIMIT " + days);
        return mapper.selectList(wrapper);
    }

    public MockResourceLoad searchLatestLoad(String resourceId) {
        LambdaQueryWrapper<MockResourceLoad> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceLoad::getResourceId, resourceId)
               .orderByDesc(MockResourceLoad::getBeginTime)
               .last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }
}
