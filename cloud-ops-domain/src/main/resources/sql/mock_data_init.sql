-- ===========================================
-- T1: Mock 数据建表 + 初始化（多资源类型版）
-- 在 Navicat 中连接 cloud_ops 库，执行本脚本
--
-- 数据说明：
--   生产环境这些数据来自 CMDB同步 / VM时序采集 / 计费中心推送
--   这里 Mock 了多种资源类型：ecs(云主机) ebm(裸金属) phy(物理机)
--   gpu(GPU节点) nas(文件存储) disk(云硬盘) sfs(弹性文件) eip(弹性IP)
--   覆盖 ReAct 排障所需的全场景
-- ===========================================

-- -------------------------------------------
-- 1. 告警表 mock_alarm
-- 15条告警，覆盖 7 种资源类型 × 5 种故障场景
-- A001 是 ReAct 主线 Demo 的排查对象
-- -------------------------------------------
DROP TABLE IF EXISTS mock_alarm;
CREATE TABLE mock_alarm (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    alert_id     VARCHAR(32)  NOT NULL COMMENT '告警ID，业务唯一',
    resource_id  VARCHAR(32)  NOT NULL COMMENT '告警关联的资源ID',
    resource_type VARCHAR(16) NOT NULL COMMENT '资源类型：ecs/ebm/phy/gpu/nas/disk/sfs',
    severity     VARCHAR(16)  NOT NULL COMMENT '严重等级：CRITICAL/WARNING/INFO',
    msg          VARCHAR(256) NOT NULL COMMENT '告警内容',
    status       VARCHAR(16)  NOT NULL DEFAULT 'unresolved' COMMENT '状态：unresolved/resolved',
    trigger_time DATETIME     NOT NULL COMMENT '告警触发时间',
    UNIQUE KEY uk_alert_id (alert_id),
    KEY idx_resource (resource_id),
    KEY idx_type (resource_type),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock告警表';

INSERT INTO mock_alarm (alert_id, resource_id, resource_type, severity, msg, status, trigger_time) VALUES
-- CPU 类
('A001', 'ecs-001', 'ecs', 'CRITICAL', 'CPU使用率持续10分钟超过90%，当前95.2%', 'unresolved', '2026-07-01 14:00:00'),
('A002', 'ebm-001', 'ebm', 'CRITICAL', '裸金属内存使用率超过92%，疑似内存泄漏', 'unresolved', '2026-07-01 14:10:00'),
-- 磁盘/存储类
('A003', 'phy-001', 'phy', 'CRITICAL', '物理机系统盘使用率达到97%，剩余空间不足5GB', 'unresolved', '2026-07-01 14:20:00'),
('A004', 'nas-001', 'nas', 'WARNING',  'NAS文件存储容量使用率达到88%，建议扩容', 'unresolved', '2026-07-01 14:25:00'),
('A005', 'disk-003','disk','WARNING',  '云硬盘IOPS持续5分钟超过2000，可能影响性能', 'resolved',   '2026-07-01 13:00:00'),
('A006', 'sfs-001', 'sfs', 'WARNING',  '弹性文件服务吞吐量下降30%，可能存在瓶颈', 'unresolved', '2026-07-01 14:35:00'),
-- 实例状态类
('A007', 'ecs-005', 'ecs', 'CRITICAL', '实例状态异常，健康检查连续失败3次，可能已宕机', 'unresolved', '2026-07-01 14:30:00'),
('A008', 'ebm-002', 'ebm', 'WARNING',  '裸金属温度传感器告警，CPU温度82°C接近阈值', 'unresolved', '2026-07-01 14:40:00'),
-- 网络类
('A009', 'ecs-002', 'ecs', 'WARNING',  '网络入带宽峰值超过800Mbps', 'resolved',   '2026-07-01 13:00:00'),
('A010', 'eip-001', 'eip', 'INFO',     '弹性IP带宽利用率达到75%', 'resolved',       '2026-07-01 12:30:00'),
-- GPU类
('A011', 'gpu-001', 'gpu', 'CRITICAL', 'GPU显存使用率超过95%，可能导致OOM', 'unresolved', '2026-07-01 14:45:00'),
('A012', 'gpu-002', 'gpu', 'WARNING',  'GPU利用率持续30分钟低于10%，资源闲置', 'unresolved', '2026-07-01 14:50:00'),
-- 综合类
('A013', 'ecs-007', 'ecs', 'WARNING',  '云主机网络丢包率达到2%', 'unresolved', '2026-07-01 14:55:00'),
('A014', 'phy-002', 'phy', 'WARNING',  '物理机CPU负载不均衡，核间差异超过40%', 'unresolved', '2026-07-01 15:00:00'),
('A015', 'ecs-009', 'ecs', 'CRITICAL', '云主机磁盘IO等待时间超过500ms', 'unresolved', '2026-07-01 15:05:00');

-- -------------------------------------------
-- 2. 资源关系表 mock_resource_relation
-- 多种资源类型的拓扑关系
-- ecs→disk(挂载) ecs→eip(绑定) ecs→nas(挂载文件存储)
-- ebm→disk ebm→eip phy→disk gpu→ecs(绑定GPU)
-- sfs→ecs(共享挂载)
-- -------------------------------------------
DROP TABLE IF EXISTS mock_resource_relation;
CREATE TABLE mock_resource_relation (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    resource_id   VARCHAR(32)  NOT NULL COMMENT '主资源ID',
    related_id    VARCHAR(32)  NOT NULL COMMENT '关联资源ID',
    relation_type VARCHAR(16)  NOT NULL COMMENT '关联类型：mount/eip/nas/gpu_bind/share',
    relation_desc VARCHAR(128) COMMENT '关系描述',
    KEY idx_resource (resource_id),
    KEY idx_related (related_id),
    KEY idx_type (relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock资源关系表';

INSERT INTO mock_resource_relation (resource_id, related_id, relation_type, relation_desc) VALUES
-- ecs-001: 主线排查对象，挂2盘+1EIP+1NAS
('ecs-001', 'disk-001', 'mount', '系统盘 100GB'),
('ecs-001', 'disk-002', 'mount', '数据盘 500GB'),
('ecs-001', 'eip-001',  'eip',   '弹性公网IP 10.0.1.101'),
('ecs-001', 'nas-001',  'nas',   '挂载NAS文件存储 /mnt/nas'),
-- ecs-002: 挂1盘+1EIP
('ecs-002', 'disk-003', 'mount', '系统盘 100GB'),
('ecs-002', 'eip-002',  'eip',   '弹性公网IP 10.0.1.102'),
-- ecs-003: 挂2盘+1EIP（数据库节点）
('ecs-003', 'disk-004', 'mount', '系统盘 50GB'),
('ecs-003', 'disk-005', 'mount', '数据盘 200GB'),
('ecs-003', 'eip-003',  'eip',   '弹性公网IP 10.0.1.103'),
-- ecs-004: 挂1盘+1EIP
('ecs-004', 'disk-006', 'mount', '系统盘 100GB'),
('ecs-004', 'eip-004',  'eip',   '弹性公网IP 10.0.1.104'),
-- ecs-005: 宕机告警，挂1盘+1EIP
('ecs-005', 'disk-007', 'mount', '系统盘 50GB'),
('ecs-005', 'eip-005',  'eip',   '弹性公网IP 10.0.1.105'),
-- ecs-006~010
('ecs-006', 'disk-008', 'mount', '系统盘 100GB'),
('ecs-006', 'eip-006',  'eip',   '弹性公网IP 10.0.1.106'),
('ecs-007', 'disk-009', 'mount', '系统盘 100GB'),
('ecs-007', 'eip-007',  'eip',   '弹性公网IP 10.0.1.107'),
('ecs-008', 'disk-010', 'mount', '系统盘 100GB'),
('ecs-008', 'eip-008',  'eip',   '弹性公网IP 10.0.1.108'),
('ecs-009', 'disk-011', 'mount', '系统盘 50GB'),
('ecs-009', 'disk-012', 'mount', '数据盘 300GB'),
('ecs-009', 'eip-009',  'eip',   '弹性公网IP 10.0.1.109'),
('ecs-010', 'disk-013', 'mount', '系统盘 100GB'),
('ecs-010', 'eip-010',  'eip',   '弹性公网IP 10.0.1.110'),
-- ebm-001: 裸金属，内存泄漏告警，挂2盘+1EIP
('ebm-001', 'disk-014', 'mount', '本地盘 1TB'),
('ebm-001', 'disk-015', 'mount', '本地盘 2TB'),
('ebm-001', 'eip-011',  'eip',   '弹性公网IP 10.0.2.101'),
-- ebm-002: 温度告警
('ebm-002', 'disk-016', 'mount', '本地盘 1TB'),
('ebm-002', 'eip-012',  'eip',   '弹性公网IP 10.0.2.102'),
-- ebm-003: 正常
('ebm-003', 'disk-017', 'mount', '本地盘 1TB'),
('ebm-003', 'eip-013',  'eip',   '弹性公网IP 10.0.2.103'),
-- phy-001: 物理机磁盘满告警
('phy-001', 'disk-018', 'mount', '系统盘 200GB'),
('phy-001', 'disk-019', 'mount', '数据盘 4TB'),
-- phy-002: CPU负载不均衡告警
('phy-002', 'disk-020', 'mount', '系统盘 200GB'),
-- gpu-001: GPU显存OOM告警，绑定到ecs-007
('gpu-001', 'ecs-007',  'gpu_bind', 'GPU卡 910B 绑定到ecs-007'),
-- gpu-002: GPU闲置告警，绑定到ecs-008
('gpu-002', 'ecs-008',  'gpu_bind', 'GPU卡 910B 绑定到ecs-008'),
-- sfs-001: 弹性文件服务，共享给ecs-003和ecs-004
('sfs-001', 'ecs-003',  'share', 'SFS共享挂载到ecs-003 /mnt/sfs'),
('sfs-001', 'ecs-004',  'share', 'SFS共享挂载到ecs-004 /mnt/sfs'),
-- nas-001: NAS文件存储，共享给ecs-001和ecs-002
('nas-001', 'ecs-001',  'share', 'NAS共享挂载到ecs-001 /mnt/nas'),
('nas-001', 'ecs-002',  'share', 'NAS共享挂载到ecs-002 /mnt/nas');

-- -------------------------------------------
-- 3. 资源负载表 mock_resource_load
-- 17台机器 × 7天 = 119条
-- 包含 ecs(10) + ebm(3) + phy(2) + gpu(2)
-- ecs-001 是故障机：CPU均值85峰值98
-- ebm-001 是内存泄漏：mem持续92+
-- gpu-002 是闲置：CPU/GPU利用率<10
-- -------------------------------------------
DROP TABLE IF EXISTS mock_resource_load;
CREATE TABLE mock_resource_load (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    resource_id   VARCHAR(32)  NOT NULL COMMENT '资源ID',
    resource_type VARCHAR(16)  NOT NULL COMMENT '资源类型：ecs/ebm/phy/gpu',
    begin_time  DATE         NOT NULL COMMENT '统计日期',
    cpu_avg     DECIMAL(5,2) NOT NULL COMMENT 'CPU平均利用率%',
    cpu_max     DECIMAL(5,2) NOT NULL COMMENT 'CPU峰值利用率%',
    mem_avg     DECIMAL(5,2) NOT NULL COMMENT '内存平均利用率%',
    mem_max     DECIMAL(5,2) NOT NULL COMMENT '内存峰值利用率%',
    UNIQUE KEY uk_resource_date (resource_id, begin_time),
    KEY idx_resource (resource_id),
    KEY idx_type (resource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock资源负载表';

-- ============ ecs-001: CPU持续高位（主线Demo故障机） ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('ecs-001', 'ecs', '2026-06-25', 83.50, 95.20, 58.30, 62.10),
('ecs-001', 'ecs', '2026-06-26', 85.10, 96.80, 60.20, 65.40),
('ecs-001', 'ecs', '2026-06-27', 84.70, 97.50, 59.80, 63.20),
('ecs-001', 'ecs', '2026-06-28', 86.30, 98.10, 61.50, 66.80),
('ecs-001', 'ecs', '2026-06-29', 85.90, 97.80, 60.10, 64.50),
('ecs-001', 'ecs', '2026-06-30', 87.20, 98.50, 62.30, 67.10),
('ecs-001', 'ecs', '2026-07-01', 88.50, 98.90, 63.80, 68.40);

-- ============ ecs-002~010: 正常负载 ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('ecs-002', 'ecs', '2026-06-25', 35.20, 52.10, 40.30, 45.60),
('ecs-002', 'ecs', '2026-06-26', 38.50, 55.30, 42.10, 48.20),
('ecs-002', 'ecs', '2026-06-27', 36.80, 51.70, 41.50, 46.80),
('ecs-002', 'ecs', '2026-06-28', 40.10, 58.40, 43.20, 49.50),
('ecs-002', 'ecs', '2026-06-29', 37.30, 53.90, 42.80, 47.10),
('ecs-002', 'ecs', '2026-06-30', 39.70, 56.20, 44.10, 50.30),
('ecs-002', 'ecs', '2026-07-01', 41.50, 59.80, 45.20, 51.70),
('ecs-003', 'ecs', '2026-06-25', 25.10, 38.50, 30.20, 35.10),
('ecs-003', 'ecs', '2026-06-26', 27.30, 41.20, 32.50, 37.80),
('ecs-003', 'ecs', '2026-06-27', 26.80, 40.10, 31.80, 36.50),
('ecs-003', 'ecs', '2026-06-28', 28.50, 42.70, 33.10, 38.20),
('ecs-003', 'ecs', '2026-06-29', 25.90, 39.30, 30.80, 35.60),
('ecs-003', 'ecs', '2026-06-30', 27.10, 41.50, 32.20, 37.10),
('ecs-003', 'ecs', '2026-07-01', 29.30, 43.80, 34.50, 39.20),
('ecs-004', 'ecs', '2026-06-25', 45.20, 62.10, 50.30, 55.60),
('ecs-004', 'ecs', '2026-06-26', 48.50, 65.30, 52.10, 58.20),
('ecs-004', 'ecs', '2026-06-27', 46.80, 61.70, 51.50, 56.80),
('ecs-004', 'ecs', '2026-06-28', 50.10, 68.40, 53.20, 59.50),
('ecs-004', 'ecs', '2026-06-29', 47.30, 63.90, 52.80, 57.10),
('ecs-004', 'ecs', '2026-06-30', 49.70, 66.20, 54.10, 60.30),
('ecs-004', 'ecs', '2026-07-01', 51.50, 69.80, 55.20, 61.70),
('ecs-005', 'ecs', '2026-06-25', 15.20, 22.10, 20.30, 25.60),
('ecs-005', 'ecs', '2026-06-26', 18.50, 25.30, 22.10, 28.20),
('ecs-005', 'ecs', '2026-06-27', 16.80, 21.70, 21.50, 26.80),
('ecs-005', 'ecs', '2026-06-28', 20.10, 28.40, 23.20, 29.50),
('ecs-005', 'ecs', '2026-06-29', 17.30, 23.90, 22.80, 27.10),
('ecs-005', 'ecs', '2026-06-30', 19.70, 26.20, 24.10, 30.30),
('ecs-005', 'ecs', '2026-07-01', 5.20, 8.10, 10.30, 12.60),
('ecs-006', 'ecs', '2026-06-25', 30.20, 45.10, 35.30, 40.60),
('ecs-006', 'ecs', '2026-06-26', 32.50, 48.30, 37.10, 42.20),
('ecs-006', 'ecs', '2026-06-27', 31.80, 46.70, 36.50, 41.80),
('ecs-006', 'ecs', '2026-06-28', 33.10, 49.40, 38.20, 43.50),
('ecs-006', 'ecs', '2026-06-29', 30.90, 47.30, 36.80, 42.10),
('ecs-006', 'ecs', '2026-06-30', 32.10, 48.50, 37.20, 42.60),
('ecs-006', 'ecs', '2026-07-01', 34.30, 50.80, 39.50, 44.20),
('ecs-007', 'ecs', '2026-06-25', 55.20, 72.10, 60.30, 65.60),
('ecs-007', 'ecs', '2026-06-26', 58.50, 75.30, 62.10, 68.20),
('ecs-007', 'ecs', '2026-06-27', 56.80, 71.70, 61.50, 66.80),
('ecs-007', 'ecs', '2026-06-28', 60.10, 78.40, 63.20, 69.50),
('ecs-007', 'ecs', '2026-06-29', 57.30, 73.90, 62.80, 67.10),
('ecs-007', 'ecs', '2026-06-30', 59.70, 76.20, 64.10, 70.30),
('ecs-007', 'ecs', '2026-07-01', 61.50, 79.80, 65.20, 71.70),
('ecs-008', 'ecs', '2026-06-25', 22.20, 35.10, 28.30, 33.60),
('ecs-008', 'ecs', '2026-06-26', 25.50, 38.30, 30.10, 35.20),
('ecs-008', 'ecs', '2026-06-27', 23.80, 36.70, 29.50, 34.80),
('ecs-008', 'ecs', '2026-06-28', 26.10, 39.40, 31.20, 36.50),
('ecs-008', 'ecs', '2026-06-29', 24.30, 37.30, 30.80, 35.10),
('ecs-008', 'ecs', '2026-06-30', 25.70, 38.50, 31.20, 36.60),
('ecs-008', 'ecs', '2026-07-01', 27.30, 40.80, 32.50, 38.20),
('ecs-009', 'ecs', '2026-06-25', 42.20, 58.10, 48.30, 53.60),
('ecs-009', 'ecs', '2026-06-26', 45.50, 61.30, 50.10, 55.20),
('ecs-009', 'ecs', '2026-06-27', 43.80, 59.70, 49.50, 54.80),
('ecs-009', 'ecs', '2026-06-28', 46.10, 62.40, 51.20, 56.50),
('ecs-009', 'ecs', '2026-06-29', 44.30, 60.30, 50.80, 55.10),
('ecs-009', 'ecs', '2026-06-30', 45.70, 61.50, 51.20, 56.60),
('ecs-009', 'ecs', '2026-07-01', 47.30, 63.80, 52.50, 57.20),
('ecs-010', 'ecs', '2026-06-25', 38.20, 52.10, 44.30, 49.60),
('ecs-010', 'ecs', '2026-06-26', 41.50, 55.30, 46.10, 51.20),
('ecs-010', 'ecs', '2026-06-27', 39.80, 53.70, 45.50, 50.80),
('ecs-010', 'ecs', '2026-06-28', 42.10, 56.40, 47.20, 52.50),
('ecs-010', 'ecs', '2026-06-29', 40.30, 54.30, 46.80, 51.10),
('ecs-010', 'ecs', '2026-06-30', 41.70, 55.50, 47.20, 52.60),
('ecs-010', 'ecs', '2026-07-01', 43.30, 57.80, 48.50, 53.20);

-- ============ ebm-001: 内存泄漏（mem持续92+） ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('ebm-001', 'ebm', '2026-06-25', 55.20, 68.10, 78.30, 82.60),
('ebm-001', 'ebm', '2026-06-26', 58.50, 71.30, 82.10, 86.20),
('ebm-001', 'ebm', '2026-06-27', 56.80, 69.70, 85.50, 88.80),
('ebm-001', 'ebm', '2026-06-28', 60.10, 74.40, 88.20, 91.50),
('ebm-001', 'ebm', '2026-06-29', 57.30, 72.90, 90.80, 93.10),
('ebm-001', 'ebm', '2026-06-30', 59.70, 73.20, 92.10, 95.30),
('ebm-001', 'ebm', '2026-07-01', 61.50, 76.80, 93.50, 96.70);

-- ============ ebm-002: 温度告警（CPU正常但温度高） ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('ebm-002', 'ebm', '2026-06-25', 65.20, 78.10, 55.30, 60.60),
('ebm-002', 'ebm', '2026-06-26', 68.50, 81.30, 57.10, 62.20),
('ebm-002', 'ebm', '2026-06-27', 66.80, 79.70, 56.50, 61.80),
('ebm-002', 'ebm', '2026-06-28', 70.10, 84.40, 58.20, 63.50),
('ebm-002', 'ebm', '2026-06-29', 67.30, 82.90, 57.80, 62.10),
('ebm-002', 'ebm', '2026-06-30', 69.70, 83.20, 59.10, 64.30),
('ebm-002', 'ebm', '2026-07-01', 72.50, 86.80, 60.20, 65.70);

-- ============ ebm-003: 正常 ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('ebm-003', 'ebm', '2026-06-25', 40.20, 55.10, 45.30, 50.60),
('ebm-003', 'ebm', '2026-06-26', 42.50, 58.30, 47.10, 52.20),
('ebm-003', 'ebm', '2026-06-27', 41.80, 56.70, 46.50, 51.80),
('ebm-003', 'ebm', '2026-06-28', 43.10, 59.40, 48.20, 53.50),
('ebm-003', 'ebm', '2026-06-29', 41.30, 57.30, 47.80, 52.10),
('ebm-003', 'ebm', '2026-06-30', 42.70, 58.50, 48.20, 53.10),
('ebm-003', 'ebm', '2026-07-01', 44.30, 60.80, 49.50, 54.20);

-- ============ phy-001: 磁盘满（CPU/Mem正常，磁盘是独立告警） ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('phy-001', 'phy', '2026-06-25', 35.20, 48.10, 40.30, 45.60),
('phy-001', 'phy', '2026-06-26', 37.50, 51.30, 42.10, 47.20),
('phy-001', 'phy', '2026-06-27', 36.80, 49.70, 41.50, 46.80),
('phy-001', 'phy', '2026-06-28', 38.10, 52.40, 43.20, 48.50),
('phy-001', 'phy', '2026-06-29', 36.30, 50.30, 42.80, 47.10),
('phy-001', 'phy', '2026-06-30', 37.70, 51.50, 43.20, 48.10),
('phy-001', 'phy', '2026-07-01', 39.30, 53.80, 44.50, 49.20);

-- ============ phy-002: CPU负载不均衡 ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('phy-002', 'phy', '2026-06-25', 50.20, 75.10, 48.30, 53.60),
('phy-002', 'phy', '2026-06-26', 52.50, 78.30, 50.10, 55.20),
('phy-002', 'phy', '2026-06-27', 51.80, 76.70, 49.50, 54.80),
('phy-002', 'phy', '2026-06-28', 53.10, 79.40, 51.20, 56.50),
('phy-002', 'phy', '2026-06-29', 51.30, 77.30, 50.80, 55.10),
('phy-002', 'phy', '2026-06-30', 52.70, 78.50, 51.20, 56.10),
('phy-002', 'phy', '2026-07-01', 54.30, 80.80, 52.50, 57.20);

-- ============ gpu-001: 显存OOM（CPU正常，但显存高——mem_max反映显存） ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('gpu-001', 'gpu', '2026-06-25', 75.20, 88.10, 82.30, 88.60),
('gpu-001', 'gpu', '2026-06-26', 78.50, 91.30, 85.10, 91.20),
('gpu-001', 'gpu', '2026-06-27', 76.80, 89.70, 87.50, 93.80),
('gpu-001', 'gpu', '2026-06-28', 80.10, 92.40, 89.20, 95.50),
('gpu-001', 'gpu', '2026-06-29', 77.30, 90.90, 88.80, 94.10),
('gpu-001', 'gpu', '2026-06-30', 79.70, 92.20, 90.10, 96.30),
('gpu-001', 'gpu', '2026-07-01', 82.50, 94.80, 92.50, 97.70);

-- ============ gpu-002: 闲置（利用率<10，成本优化场景数据源） ============
INSERT INTO mock_resource_load (resource_id, resource_type, begin_time, cpu_avg, cpu_max, mem_avg, mem_max) VALUES
('gpu-002', 'gpu', '2026-06-25', 3.20, 5.10, 8.30, 10.60),
('gpu-002', 'gpu', '2026-06-26', 4.50, 6.30, 9.10, 11.20),
('gpu-002', 'gpu', '2026-06-27', 3.80, 5.70, 8.50, 10.80),
('gpu-002', 'gpu', '2026-06-28', 5.10, 7.40, 10.20, 12.50),
('gpu-002', 'gpu', '2026-06-29', 4.30, 6.30, 9.80, 11.10),
('gpu-002', 'gpu', '2026-06-30', 4.70, 6.50, 10.20, 12.10),
('gpu-002', 'gpu', '2026-07-01', 5.30, 7.80, 11.50, 13.20);

-- -------------------------------------------
-- 4. 账单流水表 mock_billing_stream
-- 4租户 × 2月，覆盖 ecs/ebm/phy/gpu/disk/eip/nas/sfs 全类型
-- tenant-003 的 gpu-001 月费3599但利用率高（正常）
-- tenant-003 的 gpu-002 月费3599但利用率<10%（闲置→成本优化建议）
-- -------------------------------------------
DROP TABLE IF EXISTS mock_billing_stream;
CREATE TABLE mock_billing_stream (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    stream_id           VARCHAR(32)  NOT NULL COMMENT '流水ID，业务唯一',
    billing_resource_id VARCHAR(32)  NOT NULL COMMENT '计费资源ID',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '租户ID',
    tenant_name         VARCHAR(64)  COMMENT '租户名称',
    resource_name       VARCHAR(64)  COMMENT '资源名称',
    resource_type       VARCHAR(16)  NOT NULL COMMENT '资源类型：ecs/ebm/phy/gpu/disk/eip/nas/sfs',
    type                INT          NOT NULL COMMENT '1-按量计费 2-包年包月',
    billing_period      INT          NOT NULL COMMENT '账期 yyyyMM',
    total_amount        DECIMAL(10,2) NOT NULL COMMENT '总金额',
    payable_amount      DECIMAL(10,2) NOT NULL COMMENT '应付金额',
    start_time          DATETIME     NOT NULL COMMENT '计费开始时间',
    end_time            DATETIME     NOT NULL COMMENT '计费结束时间',
    UNIQUE KEY uk_stream_id (stream_id),
    KEY idx_tenant_period (tenant_id, billing_period),
    KEY idx_resource (billing_resource_id),
    KEY idx_type (resource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Mock账单流水表';

-- ============ tenant-001 生产环境（ecs + disk + eip + nas） ============
INSERT INTO mock_billing_stream (stream_id, billing_resource_id, tenant_id, tenant_name, resource_name, resource_type, type, billing_period, total_amount, payable_amount, start_time, end_time) VALUES
('S001', 'ecs-001', 'tenant-001', '生产-web组',  '生产-web-01',    'ecs',  1, 202606, 186.50, 186.50, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S002', 'ecs-002', 'tenant-001', '生产-web组',  '生产-web-02',    'ecs',  1, 202606, 156.30, 156.30, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S003', 'ecs-003', 'tenant-001', '生产-db组',   '生产-db-01',     'ecs',  2, 202606, 899.00, 799.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S004', 'ecs-001', 'tenant-001', '生产-web组',  '生产-web-01',    'ecs',  1, 202607, 62.50,  62.50,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S005', 'ecs-002', 'tenant-001', '生产-web组',  '生产-web-02',    'ecs',  1, 202607, 52.10,  52.10,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S006', 'disk-001','tenant-001', '生产-web组',  '系统盘100GB',    'disk', 1, 202606, 30.00,  30.00,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S007', 'disk-002','tenant-001', '生产-web组',  '数据盘500GB',    'disk', 1, 202606, 150.00, 150.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S008', 'eip-001', 'tenant-001', '生产-web组',  'EIP-10.0.1.101', 'eip',  1, 202606, 45.00,  45.00,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S009', 'nas-001', 'tenant-001', '生产-web组',  'NAS文件存储1TB', 'nas',  2, 202606, 299.00, 299.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S010', 'disk-001','tenant-001', '生产-web组',  '系统盘100GB',    'disk', 1, 202607, 10.00,  10.00,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S011', 'nas-001', 'tenant-001', '生产-web组',  'NAS文件存储1TB', 'nas',  2, 202607, 99.00,  99.00,  '2026-07-01 00:00:00', '2026-07-01 23:59:59');

-- ============ tenant-002 测试环境（ecs + disk + eip + sfs） ============
INSERT INTO mock_billing_stream (stream_id, billing_resource_id, tenant_id, tenant_name, resource_name, resource_type, type, billing_period, total_amount, payable_amount, start_time, end_time) VALUES
('S012', 'ecs-004', 'tenant-002', '测试-app组',  '测试-app-01',    'ecs',  1, 202606, 132.80, 132.80, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S013', 'ecs-005', 'tenant-002', '测试-app组',  '测试-app-02',    'ecs',  1, 202606, 89.50,  89.50,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S014', 'ecs-006', 'tenant-002', '测试-db组',   '测试-db-01',     'ecs',  2, 202606, 599.00, 499.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S015', 'ecs-004', 'tenant-002', '测试-app组',  '测试-app-01',    'ecs',  1, 202607, 44.30,  44.30,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S016', 'sfs-001', 'tenant-002', '测试-db组',   'SFS弹性文件100GB','sfs',  2, 202606, 199.00, 199.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S017', 'disk-006','tenant-002', '测试-app组',  '系统盘100GB',    'disk', 1, 202606, 30.00,  30.00,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S018', 'eip-004', 'tenant-002', '测试-app组',  'EIP-10.0.1.104', 'eip',  1, 202606, 45.00,  45.00,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S019', 'sfs-001', 'tenant-002', '测试-db组',   'SFS弹性文件100GB','sfs',  2, 202607, 66.00,  66.00,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S020', 'ecs-005', 'tenant-002', '测试-app组',  '测试-app-02',    'ecs',  1, 202607, 29.80,  29.80,  '2026-07-01 00:00:00', '2026-07-01 23:59:59');

-- ============ tenant-003 AI训练环境（gpu + ecs + sfs）——成本优化场景数据源 ============
INSERT INTO mock_billing_stream (stream_id, billing_resource_id, tenant_id, tenant_name, resource_name, resource_type, type, billing_period, total_amount, payable_amount, start_time, end_time) VALUES
('S021', 'gpu-001', 'tenant-003', 'AI训练组',   'GPU训练节点-01', 'gpu',  2, 202606, 3599.00, 2999.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S022', 'gpu-002', 'tenant-003', 'AI训练组',   'GPU训练节点-02', 'gpu',  2, 202606, 3599.00, 2999.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S023', 'ecs-007', 'tenant-003', 'AI训练组',   '推理服务-01',    'ecs',  1, 202606, 98.50,  98.50,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S024', 'ecs-008', 'tenant-003', 'AI训练组',   '推理服务-02',    'ecs',  1, 202606, 78.30,  78.30,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S025', 'sfs-001', 'tenant-003', 'AI训练组',   'SFS训练数据集',   'sfs',  2, 202606, 599.00, 599.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S026', 'gpu-001', 'tenant-003', 'AI训练组',   'GPU训练节点-01', 'gpu',  2, 202607, 1199.00, 1199.00, '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S027', 'gpu-002', 'tenant-003', 'AI训练组',   'GPU训练节点-02', 'gpu',  2, 202607, 1199.00, 1199.00, '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S028', 'ecs-007', 'tenant-003', 'AI训练组',   '推理服务-01',    'ecs',  1, 202607, 32.80,  32.80,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S029', 'ecs-008', 'tenant-003', 'AI训练组',   '推理服务-02',    'ecs',  1, 202607, 26.10,  26.10,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S030', 'sfs-001', 'tenant-003', 'AI训练组',   'SFS训练数据集',   'sfs',  2, 202607, 199.00, 199.00, '2026-07-01 00:00:00', '2026-07-01 23:59:59');

-- ============ tenant-004 大数据环境（phy + ebm + disk） ============
INSERT INTO mock_billing_stream (stream_id, billing_resource_id, tenant_id, tenant_name, resource_name, resource_type, type, billing_period, total_amount, payable_amount, start_time, end_time) VALUES
('S031', 'phy-001', 'tenant-004', '大数据组',   '物理机-Hadoop-01','phy',  2, 202606, 2599.00, 2299.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S032', 'phy-002', 'tenant-004', '大数据组',   '物理机-Hadoop-02','phy',  2, 202606, 2599.00, 2299.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S033', 'ebm-001', 'tenant-004', '大数据组',   '裸金属-Spark-01', 'ebm',  2, 202606, 1899.00, 1599.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S034', 'ebm-002', 'tenant-004', '大数据组',   '裸金属-Spark-02', 'ebm',  2, 202606, 1899.00, 1599.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S035', 'ebm-003', 'tenant-004', '大数据组',   '裸金属-Spark-03', 'ebm',  2, 202606, 1899.00, 1599.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S036', 'disk-018','tenant-004', '大数据组',   '物理机系统盘200GB','disk', 1, 202606, 60.00,  60.00,  '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S037', 'disk-019','tenant-004', '大数据组',   '物理机数据盘4TB', 'disk', 1, 202606, 400.00, 400.00, '2026-06-01 00:00:00', '2026-06-30 23:59:59'),
('S038', 'phy-001', 'tenant-004', '大数据组',   '物理机-Hadoop-01','phy',  2, 202607, 866.00,  866.00,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S039', 'phy-002', 'tenant-004', '大数据组',   '物理机-Hadoop-02','phy',  2, 202607, 866.00,  866.00,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S040', 'ebm-001', 'tenant-004', '大数据组',   '裸金属-Spark-01', 'ebm',  2, 202607, 633.00,  633.00,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S041', 'ebm-002', 'tenant-004', '大数据组',   '裸金属-Spark-02', 'ebm',  2, 202607, 633.00,  633.00,  '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S042', 'ebm-003', 'tenant-004', '大数据组',   '裸金属-Spark-03', 'ebm',  2, 202607, 633.00,  633.00, '2026-07-01 00:00:00', '2026-07-01 23:59:59'),
('S043', 'disk-019','tenant-004', '大数据组',   '物理机数据盘4TB', 'disk', 1, 202607, 133.00, 133.00, '2026-07-01 00:00:00', '2026-07-01 23:59:59');

-- -------------------------------------------
-- 验证
-- -------------------------------------------
SELECT '=== Mock数据初始化完成 ===' AS info;
SELECT 'mock_alarm' AS table_name, COUNT(*) AS rows_count FROM mock_alarm
UNION ALL
SELECT 'mock_resource_relation', COUNT(*) FROM mock_resource_relation
UNION ALL
SELECT 'mock_resource_load', COUNT(*) FROM mock_resource_load
UNION ALL
SELECT 'mock_billing_stream', COUNT(*) FROM mock_billing_stream;

-- 告警按资源类型分布
SELECT resource_type, severity, COUNT(*) AS cnt FROM mock_alarm GROUP BY resource_type, severity ORDER BY resource_type;
