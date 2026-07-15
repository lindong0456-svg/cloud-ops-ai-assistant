package com.cloudops.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("mock_resource_load")
public class MockResourceLoad {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;      // ← 新增
    private String deptId;        // ← 新增
    private String resourceId;
    private String resourceType;
    private LocalDate beginTime;
    private BigDecimal cpuAvg;
    private BigDecimal cpuMax;
    private BigDecimal memAvg;
    private BigDecimal memMax;


}
