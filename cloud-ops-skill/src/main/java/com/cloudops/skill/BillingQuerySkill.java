package com.cloudops.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudops.entity.MockBillingStream;
import com.cloudops.mapper.BillingStreamMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class BillingQuerySkill {

    private final BillingStreamMapper mapper;

    public BillingQuerySkill(BillingStreamMapper mapper) { this.mapper = mapper; }

    public List<MockBillingStream> searchByTenant(String tenantId, int billingPeriod) {
        LambdaQueryWrapper<MockBillingStream> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockBillingStream::getTenantId, tenantId)
               .eq(MockBillingStream::getBillingPeriod, billingPeriod)
               .orderByDesc(MockBillingStream::getTotalAmount);
        return mapper.selectList(wrapper);
    }

    public List<MockBillingStream> searchByResource(String resourceId) {
        LambdaQueryWrapper<MockBillingStream> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MockBillingStream::getBillingResourceId, resourceId)
               .orderByDesc(MockBillingStream::getBillingPeriod);
        return mapper.selectList(wrapper);
    }
}
