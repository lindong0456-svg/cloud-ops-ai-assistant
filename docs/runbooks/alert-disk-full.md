# 磁盘空间满告警处理

## 告警规则

告警名称：`DiskSpaceHigh`

PromQL 表达式：

```promql
# 磁盘使用率（核心告警）
1 - node_filesystem_avail_bytes{fstype=~"ext4|xfs",mountpoint!~"/var/lib/(docker|kubelet).*"} / node_filesystem_size_bytes{fstype=~"ext4|xfs"} > 0.85

# inode 使用率（辅助告警，小文件堆积场景）
1 - node_filesystem_files_free / node_filesystem_files > 0.85
```

指标说明：`node_filesystem_avail_bytes` 是 Node Exporter 采集的文件系统可用空间（字节），`node_filesystem_size_bytes` 是总空间，两者之比取反即为使用率。`fstype` 标签过滤 ext4/xfs 文件系统，排除 tmpfs 等伪文件系统。`mountpoint` 排除容器运行时目录避免重复计算。inode 使用率用于检测小文件堆积（如日志碎片、临时缓存）。

触发条件：磁盘使用率超过 85%（P2 预警）、超过 90%（P1 严重）、超过 95%（P0 紧急）。该告警覆盖所有 Kubernetes 节点、VictoriaMetrics 存储节点及 MongoDB 报表节点，是云管平台最高频告警之一。数据通过 dispatch（端口 9091）→ cspm → adapter 采集写入 VM，XXL-JOB 每 3 小时同步至 MongoDB 生成磁盘报表。

## 告警分级

| 级别 | 阈值条件 | 响应时效 | 通知方式 |
|------|---------|---------|---------|
| P0 | 磁盘使用率 > 95% 或 inode > 95% | 5 分钟内 | 电话 + 钉钉 + 短信 |
| P1 | 磁盘使用率 > 90% 持续 10min | 15 分钟内 | 电话 + 钉钉告警卡片 |
| P2 | 磁盘使用率 > 85% | 30 分钟内 | 钉钉群机器人推送 |
| P3 | 磁盘使用率 > 80%（趋势预警） | 巡检处理 | 钉钉群消息 |

P0 级别会触发 kubelet `DiskPressure` 条件，导致节点上所有 Pod 被 Evicted，必须立即处理。

## 影响评估

磁盘满将导致：kubelet 触发 DiskPressure 驱逐所有 Pod、VictoriaMetrics 写入失败导致指标丢失、MongoDB 报表数据写入失败、容器运行时无法拉取镜像导致 Pod 启动失败、日志无法写入影响排障。若发生在 VM 存储节点，200+ 指标采集链路中断；若发生在 MongoDB 节点，XXL-JOB 同步任务失败，影响 POC2/POC4/POC7/POC15 环境的报表生成。

## 关联组件

- **kubelet**：磁盘满触发 DiskPressure，驱逐节点上所有 Pod
- **containerd/docker**：容器运行时，镜像层和日志文件占用磁盘
- **VictoriaMetrics**：时序数据存储，200+ 指标持续写入，retentionPeriod 控制保留周期
- **MongoDB**：报表数据库，XXL-JOB 每 3 小时从 VM 同步数据写入
- **XXL-JOB**：定时同步任务，同步过程中产生临时文件占用磁盘
- **cAdvisor**：容器指标采集，自身产生日志和缓存文件
- **dispatch/cspm/adapter**：采集链路组件，日志输出频繁

## 排障步骤

1. **确认告警磁盘**：通过 VM `/v1/query/complex` 接口查询 `instance` 和 `mountpoint` 标签，确认是哪个节点的哪个挂载点告警，区分系统盘（`/`）与数据盘（`/data`）。PromQL：`1 - node_filesystem_avail_bytes{instance="$node",mountpoint="$mp"} / node_filesystem_size_bytes{instance="$node",mountpoint="$mp"}`。
2. **查磁盘增长趋势**：通过 `/v2/oneAgg` 接口查询近 24 小时磁盘使用率走势，计算 `deriv(...[24h])` 判断增长速率，估算达到 100% 的剩余时间。如果增速超过 5%/小时需立即清理。
3. **SSH 定位大文件**：登录节点执行 `du -sh /* 2>/dev/null | sort -rh | head -20`，逐层定位大文件目录。重点关注 `/var/lib/containerd`、`/var/log/containers`、`/data/vm`、`/data/mongodb` 等路径。
4. **清理容器日志**：检查 `/var/log/containers/` 和 `/var/lib/containerd/io.containerd.grpc.v1.cri/` 是否有未轮转日志堆积，单个容器日志不应超过 100MB。执行 `crictl logs --tail=0 <container>` 确认日志输出频率。
5. **清理镜像**：执行 `crictl rmi --prune` 清理未引用镜像层，通过 `crictl images | wc -l` 确认镜像数量是否异常。
6. **查已删除但被占用文件**：执行 `lsof | grep deleted`，查找已删除但仍被进程占用的文件，这类文件不释放磁盘空间需重启进程。
7. **检查 VM 存储膨胀**：若告警节点承载 VM，检查 `/data/vm` 目录大小，确认 `retentionPeriod` 配置。通过 `/v2/commonQuery` 查询 VM 指标写入速率：`rate(vm_rows_inserted_total[5m])`。
8. **检查 inode 耗尽**：执行 `df -i` 查看 inode 使用率，若 inode 接近 100% 但磁盘空间充足，需排查小文件堆积（通常是日志碎片或临时缓存）。

## 关键 PromQL

```promql
# 1. 磁盘使用率——核心指标
# node_filesystem_avail_bytes：可用空间，node_filesystem_size_bytes：总空间
1 - node_filesystem_avail_bytes{instance="$node",mountpoint="$mp"} / node_filesystem_size_bytes{instance="$node",mountpoint="$mp"}

# 2. 磁盘可用空间（GB）——估算剩余容量
node_filesystem_avail_bytes{mountpoint="/"} / 1024 / 1024 / 1024

# 3. 磁盘写入速率——定位异常写盘行为
# node_disk_written_bytes_total：累计写入字节数，rate 计算 5 分钟均值
rate(node_disk_written_bytes_total{instance="$node"}[5m])

# 4. inode 使用率——排查小文件堆积
1 - node_filesystem_files_free{instance="$node"} / node_filesystem_files{instance="$node"}

# 5. 磁盘使用率增长趋势——预估满盘时间
deriv(1 - node_filesystem_avail_bytes{instance="$node"} / node_filesystem_size_bytes{instance="$node"}[24h])

# 6. VM 数据写入速率——排查 VM 存储膨胀
rate(vm_rows_inserted_total[5m])

# 7. 磁盘 IOPS——读写频率监控
rate(node_disk_reads_completed_total[5m]) + rate(node_disk_writes_completed_total[5m])
```

## 真实案例

**时间线**：2024 年 5 月，联通数科 MSP POC7 测试集群。

**T+0min**：POC7 节点 `node-07-prod` 磁盘使用率达 98%，P0 告警触发，kubelet 报错 `DiskPressure`，大量 Pod 被 Evicted。

**T+3min**：值班人员通过 VM `/v2/oneAgg` 查询确认根分区使用率 98%，inode 使用率 67%（排除小文件问题）。`deriv` 显示 24 小时增长约 30GB，增速约 1.25GB/小时。

**T+8min**：SSH 登录节点，`du -sh /*` 定位到 `/var/lib/containerd` 占用 80GB，`/data/vm` 占用 120GB，`/var/log/containers` 占用 25GB。

**T+12min**：进一步排查 `/var/log/containers`，发现单个 Pod `cspm-ms-adapter` 日志文件膨胀至 30GB，容器日志未配置轮转策略。`/var/lib/containerd` 中 `crictl images` 显示 40+ 镜像，其中 20 个为历史版本。

**T+15min**：检查 `/data/vm` 目录，VictoriaMetrics 数据目录 120GB，`retentionPeriod` 设置为 90 天，200+ 指标持续写入导致存储膨胀。该节点同时承载 XXL-JOB 每 3 小时同步 VM 数据到 MongoDB 的报表任务，同步临时文件进一步加剧磁盘压力。

**根因**：（1）容器日志未配置轮转，单 Pod 日志膨胀至 30GB；（2）镜像多版本堆积，40+ 镜像半数为历史版本；（3）VM retentionPeriod 90 天过长，200+ 指标持续写入；（4）XXL-JOB 同步临时文件未及时清理。

**处置**：（1）清理大日志文件 `truncate -s 0 /var/log/containers/cspm-ms-adapter*.log`，释放 30GB；（2）执行 `crictl rmi --prune` 清理未引用镜像，释放 25GB；（3）VM retentionPeriod 从 90 天调整为 30 天，启动参数添加 `-retentionPeriod=30d`；（4）配置容器日志轮转：`/etc/containerd/config.toml` 设置 `max_size=100MB`、`max_files=5`。磁盘使用率于 T+40min 回落至 42%。

## 自动分诊流程

AI Agent 接收 `DiskSpaceHigh` 告警后执行以下自动化分诊：

1. **告警解析**：提取 `instance`、`mountpoint` 标签，判定节点名、挂载点和告警级别。
2. **容量预估**：通过 `/v2/oneAgg` 查询 24 小时磁盘 `deriv`，计算增长速率和预计满盘时间，若预计 < 2 小时则升级为紧急。
3. **关联分析**：并行查询 VM 写入速率（`vm_rows_inserted_total`）、磁盘 I/O 速率、inode 使用率，交叉判断是数据膨胀、日志堆积还是小文件问题。
4. **自动定位**：通过 SSH 执行 `du -sh /*` 递归定位大文件目录，识别 Top10 占用路径，匹配常见膨胀模式（容器日志、镜像堆积、VM 数据、MongoDB 数据）。
5. **安全清理**：对低风险项（未引用镜像、已轮转旧日志）执行自动清理，高风险项（VM 数据、MongoDB 数据）生成清理建议等待人工确认。
6. **报表更新**：处置完成后通过 XXL-JOB 同步磁盘状态至 MongoDB，更新 POC2/POC4/POC7/POC15 磁盘容量报表。

## 处置建议

1. 配置容器日志轮转：`/etc/containerd/config.toml` 设置 `max_size=100MB`、`max_files=5`。
2. 定期执行 `crictl rmi --prune` 清理未引用镜像，加入 Crontab 每日凌晨执行。
3. VictoriaMetrics retentionPeriod 从 90 天调整为 30 天，启动参数 `-retentionPeriod=30d`。
4. 配置两级告警：85% 预警（P2）+ 95% 紧急（P0），提前介入避免 DiskPressure。
5. 存储密集型服务（MongoDB 报表库、VM 存储节点）独立挂载数据盘，与系统盘隔离。
6. XXL-JOB 同步任务完成后自动清理临时文件，避免残留堆积。

## 预防措施

1. **日志轮转全覆盖**：所有节点配置 containerd 日志轮转，`max_size=100MB`、`max_files=5`，Cron 每周审计。
2. **镜像清理自动化**：XXL-JOB 每日凌晨执行 `crictl rmi --prune`，保留最近 3 个版本。
3. **VM 保留期治理**：生产环境 retentionPeriod=30d，POC 环境 retentionPeriod=7d，避免 200+ 指标无限膨胀。
4. **磁盘容量基线**：根据 10 类产品（vm/phy/bms/ECS 等）的指标采集量，为每类节点设定磁盘容量基线，低于 20% 触发扩容。
5. **巡检报表**：XXL-JOB 每 3 小时同步磁盘指标至 MongoDB，生成 POC2/POC4/POC7/POC15 磁盘容量趋势报表。

## 相关 PromQL 速查

```promql
# 磁盘使用率
1 - node_filesystem_avail_bytes{instance="$node"} / node_filesystem_size_bytes{instance="$node"}

# 磁盘可用空间（GB）
node_filesystem_avail_bytes{mountpoint="/"} / 1024 / 1024 / 1024

# inode 使用率
1 - node_filesystem_files_free / node_filesystem_files

# 磁盘写入速率
rate(node_disk_written_bytes_total[5m])

# 磁盘使用率 24h 趋势
deriv(1 - node_filesystem_avail_bytes / node_filesystem_size_bytes[24h])

# VM 数据写入速率
rate(vm_rows_inserted_total[5m])

# 磁盘 IOPS
rate(node_disk_reads_completed_total[5m]) + rate(node_disk_writes_completed_total[5m])

# 磁盘读写延迟
rate(node_disk_io_time_seconds_total[5m])
```
