# NAS 文件存储容量告警排查 SOP

## 告警规则

告警名称：`NASCapacityHigh`

PromQL 表达式：

```promql
# NAS 文件系统容量使用率（核心告警）
(node_filesystem_size_bytes{fstype="nfs",mountpoint=~"/mnt/nas.*"} - node_filesystem_avail_bytes{fstype="nfs",mountpoint=~"/mnt/nas.*"}) / node_filesystem_size_bytes{fstype="nfs",mountpoint=~"/mnt/nas.*"} > 0.85

# NAS 文件系统 inode 使用率（辅助告警，小文件堆积场景）
1 - node_filesystem_files_free{fstype="nfs"} / node_filesystem_files{fstype="nfs"} > 0.85
```

指标说明：`node_filesystem_size_bytes` 为 NAS 挂载点总容量，`node_filesystem_avail_bytes` 为可用容量，两者之差为已用容量。`fstype="nfs"` 过滤 NFS 协议文件系统，`mountpoint` 匹配 `/mnt/nas` 前缀的挂载路径。inode 使用率用于检测小文件堆积场景（如日志碎片、临时缓存文件）。

触发条件：NAS 容量使用率超过 85%（P2 预警）、超过 90%（P1 严重）、超过 95%（P0 紧急）。该告警覆盖联通云 SFS 弹性文件服务、自建 NAS 存储及挂载至 ECS/BMS 的共享文件存储，数据通过 dispatch（端口 9091）→ cspm → adapter 采集写入 VictoriaMetrics。

## 告警分级

| 级别 | 阈值条件 | 响应时效 | 通知方式 |
|------|---------|---------|---------|
| P0 | NAS 使用率 > 95% 或 inode > 95% | 5 分钟内 | 电话 + 钉钉 + 短信 |
| P1 | NAS 使用率 > 90% 持续 10min | 15 分钟内 | 电话 + 钉钉告警卡片 |
| P2 | NAS 使用率 > 85% | 30 分钟内 | 钉钉群机器人推送 |
| P3 | NAS 使用率 > 80%（趋势预警） | 巡检处理 | 钉钉群消息 |

P0 级别意味着 NAS 即将写满，所有依赖该存储的 Pod 和业务系统将无法写入数据，必须立即处理。

## 影响评估

NAS 存储写满将导致：依赖共享存储的应用无法写入数据（日志、临时文件、模型权重等）、Kubernetes PV 挂载 NAS 的 Pod 进入 `ReadWriteError` 状态、AI 训练任务无法保存 checkpoint 导致训练中断、XXL-JOB 报表导出任务写入临时文件失败、CMDB 资产同步脚本无法输出结果文件。若发生在 AICP 算力平台的模型存储 NAS，将导致 GPU 训练任务全部中断；若发生在政务云共享存储，将影响多租户业务数据读写。

## 关联组件

- **dispatch（端口 9091）**：指标采集网关，NAS 挂载点指标通过 Node Exporter 采集后汇聚至 dispatch
- **cspm**：云安全策略管理，负责 NAS 访问权限策略下发
- **adapter**：协议适配层，将 NAS 容量指标转换为统一格式写入 VictoriaMetrics
- **VictoriaMetrics**：时序数据库，存储 NAS 容量、IOPS、吞吐等 200+ 指标
- **Kubernetes PV/PVC**：挂载 NAS 的持久卷，容量不足时 Pod 写入失败
- **XXL-JOB**：定时任务，报表导出依赖 NAS 临时目录写入
- **CMDB 同步服务**：资产数据定期导出至 NAS 共享目录备份

## 排障步骤

1. **确认告警 NAS 挂载点**：通过 VM `/v1/query/complex` 接口查询 `instance` 和 `mountpoint` 标签，确认是哪个节点的哪个 NAS 挂载点告警。PromQL：`(node_filesystem_size_bytes{fstype="nfs",instance="$node",mountpoint="$mp"} - node_filesystem_avail_bytes{fstype="nfs",instance="$node",mountpoint="$mp"}) / node_filesystem_size_bytes{fstype="nfs",instance="$node",mountpoint="$mp"}`。区分系统 NAS（`/mnt/nas-system`）与业务 NAS（`/mnt/nas-data`）。
2. **查容量增长趋势**：通过 `/v2/oneAgg` 接口查询近 24 小时 NAS 使用率走势，计算 `deriv(...[24h])` 判断增长速率，估算达到 100% 的剩余时间。如果增速超过 3%/小时需立即清理。
3. **SSH 定位大文件目录**：登录挂载节点执行 `du -sh /mnt/nas/* 2>/dev/null | sort -rh | head -20`，逐层定位大文件目录。重点关注模型权重目录、日志归档目录、报表导出目录、训练 checkpoint 目录。
4. **检查快照占用**：若 NAS 为联通云 SFS 弹性文件服务，通过云控制台查看是否存在自动快照策略，快照会占用 NAS 后端存储空间。执行 `df -h /mnt/nas` 对比 `df -h` 总量，差异过大通常是快照导致。
5. **统计文件数量与大小分布**：执行 `find /mnt/nas -type f -size +1G | head -20` 定位超大文件，执行 `find /mnt/nas -type f | wc -l` 统计总文件数，判断是否为小文件堆积。
6. **检查生命周期策略**：通过云控制台查看 NAS 是否配置了生命周期管理策略（如 30 天未访问自动转低频、90 天自动删除），未配置则需手动清理过期数据。
7. **检查 NFS 挂载参数**：执行 `mount | grep nfs` 确认挂载参数，重点关注 `rsize`/`wsize`（读写块大小）、`hard`/`soft`（挂载模式）、`timeo`（超时时间），不合理的参数可能导致写入性能劣化间接导致空间增长。
8. **检查 inode 耗尽**：执行 `df -i /mnt/nas` 查看 inode 使用率，若 inode 接近 100% 但磁盘空间充足，需排查小文件堆积（通常是日志碎片或临时缓存），通过 `find /mnt/nas -type f -size 0 | wc -l` 统计空文件数量。

## 关键 PromQL

```promql
# 1. NAS 容量使用率——核心指标
# node_filesystem_avail_bytes：可用空间，node_filesystem_size_bytes：总空间
(node_filesystem_size_bytes{fstype="nfs",instance="$node",mountpoint="$mp"} - node_filesystem_avail_bytes{fstype="nfs",instance="$node",mountpoint="$mp"}) / node_filesystem_size_bytes{fstype="nfs",instance="$node",mountpoint="$mp"}

# 2. NAS 可用空间（GB）——估算剩余容量
node_filesystem_avail_bytes{fstype="nfs",mountpoint=~"/mnt/nas.*"} / 1024 / 1024 / 1024

# 3. NAS 写入速率——定位异常写入行为
rate(node_disk_written_bytes_total{instance="$node"}[5m])

# 4. NAS inode 使用率——排查小文件堆积
1 - node_filesystem_files_free{fstype="nfs"} / node_filesystem_files{fstype="nfs"}

# 5. NAS 使用率增长趋势——预估满盘时间
deriv((node_filesystem_size_bytes{fstype="nfs"} - node_filesystem_avail_bytes{fstype="nfs"}) / node_filesystem_size_bytes{fstype="nfs"}[24h])

# 6. NFS IOPS——读写频率监控
rate(node_nfs_rpc_operations_total[5m])

# 7. NFS 吞吐量——排查大文件持续写入
rate(node_disk_written_bytes_total{instance="$node"}[5m])
```

## 真实案例

**时间线**：2024 年 6 月，联通数科 MSP AICP 算力平台 POC4 集群。

**T+0min**：AICP 模型存储 NAS `nas-aicp-model-01` 使用率达 93%，P1 告警触发，钉钉告警卡片推送至值班群。该 NAS 挂载在 3 个 GPU 训练节点 `/mnt/nas-models` 路径。

**T+5min**：值班人员通过 VM `/v2/oneAgg` 查询确认 NAS 使用率 93%，24 小时 `deriv` 显示增长约 200GB，增速约 8.3GB/小时，预计 12 小时内写满。

**T+10min**：SSH 登录 GPU 节点 `gpu-node-04`，`du -sh /mnt/nas-models/*` 定位到 `/mnt/nas-models/checkpoints` 目录占用 1.2TB，`/mnt/nas-models/datasets` 占用 800GB。

**T+15min**：进一步排查 `checkpoints` 目录，发现存在大量历史训练 checkpoint 文件，单个 checkpoint 约 50GB，保留超过 30 天的 checkpoint 有 20+ 份。`datasets` 目录中多个已废弃项目的数据集未清理。

**T+20min**：检查联通云 SFS 控制台，发现该 NAS 未配置生命周期管理策略，也未配置自动快照（排除快照占用）。通过 `find /mnt/nas-models -type f -size +10G | wc -l` 统计超大文件 45 个，均为 checkpoint 和原始数据集。

**根因**：（1）AI 训练任务每次保存 checkpoint 约 50GB，未配置自动清理策略，30 天以上历史 checkpoint 堆积 20+ 份；（2）已废弃项目数据集未从 NAS 迁移至冷存储；（3）NAS 未配置生命周期管理策略。

**处置**：（1）清理 30 天以上的历史 checkpoint，保留最近 5 份，释放约 800GB；（2）将已废弃项目数据集迁移至联通云 OBS 对象存储冷存储，释放约 600GB；（3）配置 NAS 生命周期策略：30 天未访问文件自动转低频存储，90 天自动归档；（4）训练任务脚本增加 checkpoint 自动清理逻辑，仅保留最近 5 份。NAS 使用率于 T+45min 回落至 52%。

## 自动分诊流程

AI Agent 接收 `NASCapacityHigh` 告警后执行以下自动化分诊：

1. **告警解析**：提取 `instance`、`mountpoint` 标签，判定 NAS 挂载点名称、所属节点和告警级别，关联 CMDB 确认 NAS 归属的业务系统。
2. **容量预估**：通过 `/v2/oneAgg` 查询 24 小时 NAS 使用率 `deriv`，计算增长速率和预计满盘时间，若预计 < 6 小时则升级为紧急。
3. **文件分析**：通过 SSH 执行 `du -sh` 递归定位大文件目录，识别 Top10 占用路径，匹配常见膨胀模式（checkpoint 堆积、数据集未清理、日志归档、报表临时文件）。
4. **快照排查**：自动调用联通云 SFS API 查询快照策略和快照占用空间，判断是否为快照导致容量偏差。
5. **安全清理**：对低风险项（过期 checkpoint、临时报表文件、已轮转旧日志）执行自动清理，高风险项（数据集、模型权重）生成清理建议等待人工确认。
6. **报表更新**：处置完成后通过 XXL-JOB 同步 NAS 容量状态至 MongoDB，更新 POC2/POC4/POC7/POC15 NAS 容量报表。

## 处置建议

1. 配置 NAS 生命周期管理策略：30 天未访问文件自动转低频存储，90 天自动归档至 OBS。
2. AI 训练任务脚本增加 checkpoint 自动清理逻辑，仅保留最近 N 份（建议 5 份）。
3. 定期执行 `find /mnt/nas -type f -mtime +90 -delete` 清理 90 天以上未访问文件，加入 Crontab 每周执行。
4. 配置两级告警：85% 预警（P2）+ 95% 紧急（P0），提前介入避免写满。
5. 大容量 NAS 独立挂载数据盘，与系统盘隔离，避免影响节点操作系统。
6. 已废弃项目数据及时迁移至 OBS 冷存储，降低 NAS 存储成本和容量压力。

## 预防措施

1. **生命周期策略全覆盖**：所有 NAS 挂载点配置生命周期管理，30 天转低频、90 天归档，Cron 每月审计策略覆盖率。
2. **Checkpoint 自动清理**：训练任务脚本统一集成 checkpoint 清理逻辑，保留最近 5 份，多余自动删除。
3. **NAS 容量基线**：根据业务类型（模型存储、日志归档、报表临时、共享数据）为每个 NAS 设定容量基线，低于 20% 触发扩容。
4. **快照治理**：联通云 SFS 配置自动快照保留策略，最多保留 7 份，避免快照占用后端存储。
5. **巡检报表**：XXL-JOB 每 3 小时同步 NAS 容量指标至 MongoDB，生成 POC2/POC4/POC7/POC15 NAS 容量趋势报表，识别持续增长的业务目录。

## 相关 PromQL 速查

```promql
# NAS 容量使用率
(node_filesystem_size_bytes{fstype="nfs"} - node_filesystem_avail_bytes{fstype="nfs"}) / node_filesystem_size_bytes{fstype="nfs"}

# NAS 可用空间（GB）
node_filesystem_avail_bytes{fstype="nfs"} / 1024 / 1024 / 1024

# NAS inode 使用率
1 - node_filesystem_files_free{fstype="nfs"} / node_filesystem_files{fstype="nfs"}

# NAS 使用率 24h 趋势
deriv((node_filesystem_size_bytes{fstype="nfs"} - node_filesystem_avail_bytes{fstype="nfs"}) / node_filesystem_size_bytes{fstype="nfs"}[24h])

# NFS 吞吐量
rate(node_disk_written_bytes_total{instance="$node"}[5m])

# NFS IOPS
rate(node_nfs_rpc_operations_total[5m])

# NAS 挂载点数量统计
count(node_filesystem_size_bytes{fstype="nfs"}) by (instance)

# NAS 挂载点文件数统计
node_filesystem_files{fstype="nfs"}
```
