-- ============================================================
-- cloud-ops-ai-assistant 系统权限初始化脚本
-- ============================================================
-- 用途：初始化/修复 RBAC 权限表（sys_permission/sys_role/sys_user_role/sys_role_permission）
-- 执行方式：mysql -u root -p cloud_ops < init-permissions.sql
-- 注意：本脚本幂等执行，重复运行不会报错
-- ============================================================

-- ==================== 1. 权限定义表 ====================
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '权限ID',
    `permission_code` VARCHAR(64) NOT NULL              COMMENT '权限编码（如 alarm:read）',
    `permission_name` VARCHAR(100) NOT NULL             '权限中文名称',
    `module`      VARCHAR(32)  DEFAULT NULL            COMMENT '所属模块',
    `sort_order`  INT          DEFAULT 0               COMMENT '排序号',
    `status`      TINYINT      DEFAULT 1               COMMENT '1启用 0禁用',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限定义表';

-- 清空旧数据重新插入（避免脏数据）
DELETE FROM `sys_permission`;

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `sort_order`) VALUES
('agent:chat',     'AI对话权限',        'agent',   1),
('alarm:read',     '告警查询权限',      'alarm',   2),
('billing:read',   '账单查询权限',      'billing', 3),
('resource:read',  '资源查询权限',      'resource',4),
('rag:read',       '知识库查询权限',    'rag',     5);


-- ==================== 2. 角色定义表 ====================
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`        BIGINT       NOT NULL AUTO_INCREMENT,
    `role_code` VARCHAR(64) NOT NULL              COMMENT '角色编码',
    `role_name` VARCHAR(100) NOT NULL             COMMENT '角色名称',
    `status`    TINYINT      DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

DELETE FROM `sys_role`;

INSERT INTO `sys_role` (`role_code`, `role_name`) VALUES
('SUPER_ADMIN', '超级管理员'),
('ops_eng',   '运维工程师'),
('ops_viewer','运维只读用户'),
('finance',   '财务人员'),
('TENANT_ADMIN', '租户管理员');


-- ==================== 3. 用户角色关联表 ====================
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`      BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` VARCHAR(64) NOT NULL                COMMENT 'sys_user.id 或 sys_user.userId',
    `role_id` BIGINT   NOT NULL                   COMMENT 'sys_role.id',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

DELETE FROM `sys_user_role`;

-- ★ 关键：admin 用户绑定 admin 角色（id=1）
--   如果你的 admin 用户的 id 不是 1，请修改下面的 user_id 值
--   可先执行: SELECT id, username FROM sys_user WHERE username = 'admin';
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
('1', 1),         -- admin → 超级管理员
('2', 2),         -- ops_eng → 运维工程师
('3', 3),         -- ops_viewer → 运维只读
('4', 4);         -- finance → 财务人员


-- ==================== 4. 角色权限关联表 ====================
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`            BIGINT NOT NULL AUTO_INCREMENT,
    `role_id`       BIGINT NOT NULL                  COMMENT 'sys_role.id',
    `permission_id` BIGINT NOT NULL                  COMMENT 'sys_permission.id',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

DELETE FROM `sys_role_permission`;

-- ★ 权限矩阵（核心！这里决定谁能干什么）
--   admin(id=1): 全部 5 项权限
--   ops_eng(id=2): agent:chat + alarm:read + resource:read + rag:read（无 billing:read）
--   ops_viewer(id=3): agent:read + resource:read（无对话、无告警明细、无账单、无知识库）
--   finance(id=4): agent:chat + billing:read（仅对话+查账单）

-- admin → 全部权限
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) SELECT 1, id FROM `sys_permission`;

-- ops_engineer → 对话+告警+资源+知识库（无账单）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(2, 1),  -- agent:chat
(2, 2),  -- alarm:read
(2, 4),  -- resource:read
(2, 5);  -- rag:read

-- ops_viewer → 仅资源查看（最受限）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(3, 4);  -- resource:read

-- finance → 对话+账单
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(4, 1),  -- agent:chat
(4, 3);  -- billing:read


-- ==================== 5. 验证查询（执行后可手动跑这些SQL确认） ====================
-- SELECT '--- 权限总数 ---' AS '';
-- SELECT COUNT(*) AS total_perms FROM sys_permission;

-- SELECT '--- admin 的权限 ---' AS '';
-- SELECT p.permission_code, p.permission_name
-- FROM sys_permission p
-- INNER JOIN sys_role_permission rp ON rp.permission_id = p.id
-- INNER JOIN sys_user_role ur ON ur.role_id = rp.role_id
-- WHERE ur.user_id = '1'
-- ORDER BY p.permission_code;

-- SELECT '--- ops_eng 的权限（应无 billing:read 和 agent:chat 以外的）---' AS '';
-- SELECT p.permission_code, p.permission_name
-- FROM sys_permission p
-- INNER JOIN sys_role_permission rp ON rp.permission_id = p.id
-- INNER JOIN sys_user_role ur ON ur.role_id = rp.role_id
-- WHERE ur.user_id = '2'
-- ORDER BY p.permission_code;
