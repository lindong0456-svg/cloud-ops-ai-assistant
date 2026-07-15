package com.cloudops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mock_alarm")
public class MockAlarm {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;      // ← 新增
    private String deptId;        // ← 新增
    private String alertId;
    private String resourceId;
    private String resourceType;
    private String severity;
    private String msg;
    private String status;
    private LocalDateTime triggerTime;
}
