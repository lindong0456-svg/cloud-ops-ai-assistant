-- ============================================================
-- 给财务用户加 TENANT_ADMIN 角色（跨部门看账单）
-- 执行方式：mysql -u root -p cloud_ops < docs/upgrade_finance_tenant_admin.sql
-- ============================================================

-- 1. 插入 TENANT_ADMIN 角色
INSERT IGNORE INTO sys_role VALUES (5, 'TENANT_ADMIN', '租户管理员', 1);

-- 2. 把财务用户(user_id=4)绑定 TENANT_ADMIN 角色(role_id=5)
INSERT IGNORE INTO sys_user_role VALUES (NULL, '4', 5);

-- 验证
SELECT u.username, GROUP_CONCAT(r.role_code ORDER BY r.id) AS roles
FROM sys_user u
JOIN sys_user_role ur ON CAST(ur.user_id AS UNSIGNED) = u.id
JOIN sys_role r ON r.id = ur.role_id
WHERE u.username = 'finance'
GROUP BY u.username;
