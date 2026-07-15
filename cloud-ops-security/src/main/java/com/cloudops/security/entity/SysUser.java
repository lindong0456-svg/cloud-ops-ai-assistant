package com.cloudops.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;       // 业务编码: user-001
    private String username;     // 登录用户名
    private String password;     // BCrypt密文
    private String tenantId;     // 所属租户
    private String deptId;       // 所属部门
    private String email;
    private Integer status;      // 1-启用 0-禁用
    private LocalDateTime createdAt;
}
