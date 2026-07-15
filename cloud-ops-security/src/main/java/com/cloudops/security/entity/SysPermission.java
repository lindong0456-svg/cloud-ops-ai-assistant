
package com.cloudops.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_permission")
public class SysPermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String permissionCode;  // alarm:read
    private String permissionName;  // 告警查看
    private String resourceType;    // API / DATA / MENU
    private String resourceAction;  // READ / WRITE / DELETE
    private LocalDateTime createdAt;
}
