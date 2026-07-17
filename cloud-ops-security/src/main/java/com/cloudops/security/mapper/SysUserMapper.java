package com.cloudops.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudops.security.dto.PermissionInfo;
import com.cloudops.security.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 根据用户名查询用户（登录时用）
     * 用 LambdaQueryWrapper 也行，这里用注解SQL更直观
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND status = 1")
    SysUser selectByUsername(String username);

    /**
     * 查询用户的角色编码列表
     * JOIN sys_user_role + sys_role
     */
    @Select("""
        SELECT r.role_code 
        FROM sys_role r
        INNER JOIN sys_user_role ur ON ur.role_id = r.id
        WHERE ur.user_id = #{userId}
        """)
    List<String> selectRoleCodesByUserId(String userId);

    /**
     * 查询用户的权限编码列表（三表 JOIN，仅返回 code）
     * JwtPayload 构造时用
     */
    @Select("""
        SELECT DISTINCT p.permission_code
        FROM sys_permission p
        INNER JOIN sys_role_permission rp ON rp.permission_id = p.id
        INNER JOIN sys_user_role ur ON ur.role_id = rp.role_id
        WHERE ur.user_id = #{userId}
        """)
    List<String> selectPermissionCodesByUserId(String userId);

    /**
     * 查询用户的权限详细信息（含中文 permission_name）
     *
     * 返回 PermissionInfo(code, name) 供 LoginResponse 和前端展示用
     */
    @Select("""
        SELECT DISTINCT p.permission_code, p.permission_name
        FROM sys_permission p
        INNER JOIN sys_role_permission rp ON rp.permission_id = p.id
        INNER JOIN sys_user_role ur ON ur.role_id = rp.role_id
        WHERE ur.user_id = #{userId}
        ORDER BY p.permission_code
        """)
    List<PermissionInfo> selectPermissionsByUserId(String userId);
}
