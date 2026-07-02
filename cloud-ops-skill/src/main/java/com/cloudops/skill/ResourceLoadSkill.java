package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockResourceLoad;
import com.cloudops.mapper.ResourceLoadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资源负载查询 Skill — 能力内核，无 AI 感知
 *
 * 对标联通内部上云资源负载的 cpu_cloud_syn_list 预聚合设计：
 *   联通每3小时把原始大表预聚合到中间表，这里直接查Mock表（已预聚合好）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceLoadSkill {

    private final ResourceLoadMapper resourceLoadMapper;

    /**
     * 查某资源最近 N 天的负载（CPU/内存均值+峰值）
     */
    public List<MockResourceLoad> searchLoad(String resourceId, int days) {
        LambdaQueryWrapper<MockResourceLoad> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceLoad::getResourceId, resourceId)
               .orderByDesc(MockResourceLoad::getBeginTime)
               .last("LIMIT " + days);
        return resourceLoadMapper.selectList(wrapper);
    }

    /**
     * 查某资源最新一天的负载快照
     */
    public MockResourceLoad searchLatestLoad(String resourceId) {
        LambdaQueryWrapper<MockResourceLoad> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockResourceLoad::getResourceId, resourceId)
               .orderByDesc(MockResourceLoad::getBeginTime)
               .last("LIMIT 1");
        return resourceLoadMapper.selectOne(wrapper);
    }
}
