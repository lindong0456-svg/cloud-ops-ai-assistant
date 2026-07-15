package com.cloudops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("mock_billing_stream")
public class MockBillingStream {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String streamId;
    private String billingResourceId;
    private String tenantId;
    private String deptId;        // ← 新增
    private String tenantName;
    private String resourceName;
    private String resourceType;
    private Integer type;
    private Integer billingPeriod;
    private BigDecimal totalAmount;
    private BigDecimal payableAmount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
