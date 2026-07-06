package com.cloudops.skill.config;

import com.cloudops.mapper.AlarmMapper;
import com.cloudops.mapper.BillingStreamMapper;
import com.cloudops.mapper.ResourceLoadMapper;
import com.cloudops.mapper.ResourceRelationMapper;
import com.cloudops.skill.AlarmSearchSkill;
import com.cloudops.skill.BillingQuerySkill;
import com.cloudops.skill.ResourceLoadSkill;
import com.cloudops.skill.ResourceRelationSkill;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SkillConfig {
    @Bean public AlarmSearchSkill alarmSearchSkill(AlarmMapper m) { return new AlarmSearchSkill(m); }
    @Bean public BillingQuerySkill billingQuerySkill(BillingStreamMapper m) { return new BillingQuerySkill(m); }
    @Bean public ResourceLoadSkill resourceLoadSkill(ResourceLoadMapper m) { return new ResourceLoadSkill(m); }
    @Bean public ResourceRelationSkill resourceRelationSkill(ResourceRelationMapper m) { return new ResourceRelationSkill(m); }
}
