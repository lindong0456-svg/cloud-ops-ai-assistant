package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockBillingStream;
import com.cloudops.mapper.BillingStreamMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 账单查询 Skill — 能力内核，无 AI 感知
 *
 * 对标联通计费中心 msp-bill 的 billing_stream 设计：
 *   联通用按月分表 + 雪花ID，这里Mock单表 + 简单ID，面试讲分表思路即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingQuerySkill {

    private final BillingStreamMapper billingStreamMapper;

    /**
     * 按租户 + 账期查账单
     */
    public List<MockBillingStream> searchByTenant(String tenantId, int billingPeriod) {
        LambdaQueryWrapper<MockBillingStream> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockBillingStream::getTenantId, tenantId)
               .eq(MockBillingStream::getBillingPeriod, billingPeriod)
               .orderByDesc(MockBillingStream::getTotalAmount);
        return billingStreamMapper.selectList(wrapper);
    }

    /**
     * 按资源 ID 查该资源的所有账单
     */
    public List<MockBillingStream> searchByResource(String resourceId) {
        LambdaQueryWrapper<MockBillingStream> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockBillingStream::getBillingResourceId, resourceId)
               .orderByDesc(MockBillingStream::getBillingPeriod);
        return billingStreamMapper.selectList(wrapper);
    }
}
