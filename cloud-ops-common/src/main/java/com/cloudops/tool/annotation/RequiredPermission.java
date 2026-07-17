package com.cloudops.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tool 权限注解 — 标记执行此 Tool 需要的权限编码
 *
 * 用法：
 *   @RequiredPermission("rag:read")
 *   @Tool("搜索知识库")
 *   public String searchKnowledge(String query) { ... }
 *
 * 工作流程：
 *   1. AbstractTool.execute() 通过 StackWalker 获取调用方法的注解
 *   2. 读取 @RequiredPermission.value() 得到所需权限
 *   3. 调用 ToolPermissionChecker.hasPermission() 校验当前用户（Spring 注入实现）
 *   4. 无权限 → 返回 ToolResult.fail()，不执行业务逻辑
 *
 * 放在 common 模块而非 security 模块，避免循环依赖。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredPermission {

    /**
     * 所需的权限编码，对应 sys_permission.permission_code
     * 例如: "rag:read", "billing:read", "alarm:read"
     */
    String value();
}
