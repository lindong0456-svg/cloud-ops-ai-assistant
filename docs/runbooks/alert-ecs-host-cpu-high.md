# 云主机/物理机 CPU 持续高负载排查 SOP

## 告警规则

告警名称：`ECSHostCPUHigh`

PromQL 表达式：

```promql
# 节点 CPU 使用率（核心告警）
1 - avg(rate(node_cpu_seconds_total{mode="idle",instance=~"$ecs_host.*"}[5m])) by (instance) > 0.90

# 节点 load15 与核数比（辅助告警，反映 CPU 排队压力）
node_load15{instance=~"$ecs_host.*"} / on(instance) count by (instance) (node_cpu_seconds_total{mode="idle",instance=~"$ecs_host.*"}) > 2.0

# 进程级 CPU 消耗（定位具体进程）
topk(10, rate(process_cpu_seconds_total{instance=~"$ecs_host.*"}[5m]))
```

指标说明：`node_cpu_seconds_total{mode="idle"}` 是 Node Exporter 采集的节点 CPU 空闲时间累计（秒），`rate(...[5m])` 计算 5 分钟内的平均空闲率，1 减去空闲率即为 CPU 使用率。`node_load15` 是 15 分钟平均负载，除以 CPU 核数得到负载比，超过 2.0 表示每个核平均有 2 个进程在排队。`process_cpu_seconds_total` 用于定位具体消耗 CPU 的进程。

触发条件：CPU 使用率持续超过 90% 且持续 5 分钟以上。该告警覆盖联通云 ECS 弹性云服务器、EBM 弹性裸金属实例和 PHY 物理机，通过 dispatch（端口 9091）→ cspm → adapter 架构采集，数据写入 VictoriaMetrics。

## 告警分级

| 级别 | 阈值条件 | 响应时效 | 通知方式 |
|------|---------|---------|---------|
| P0 | CPU > 95% 持续 10min 且 load15/核数 > 5 | 5 分钟内 | 电话 + 钉钉 + 短信 |
| P1 | CPU > 90% 持续 5min 且影响业务接口可用性 | 15 分钟内 | 电话 + 钉钉告警卡片 |
| P2 | CPU > 90% 持续 5min | 30 分钟内 | 钉钉群机器人推送 |
| P3 | CPU > 80% 持续 15min（趋势预警） | 巡检处理 | 钉钉群消息 |

P0 级别意味着节点已接近不可用状态，SSH 登录可能卡顿，业务接口严重超时，必须立即处理。

## 影响评估

CPU 持续高负载将导致：SSH 登录延迟或超时，影响运维操作；业务接口 P99 延迟急剧升高，用户体验劣化；Kubernetes 节点上的 Pod 调度受限，新 Pod 无法调度到该节点；VictoriaMetrics 查询响应变慢，影响告警和报表的实时性；若 dispatch 网关节点（端口 9091）CPU 满载，将导致全平台指标采集延迟和丢失；若 cspm 节点 CPU 满载，将影响配置下发和策略执行。

## 关联组件

- **dispatch（端口 9091）**：指标采集网关，CPU 满载将导致采集延迟和指标丢失
- **cspm**：云安全策略管理，CPU 高负载影响策略下发实时性
- **adapter**：协议适配层，连接 VM 与上层 API，CPU 瓶颈导致查询超时
- **VictoriaMetrics**：时序数据库，复杂 PromQL 聚合查询消耗大量 CPU
- **XXL-JOB**：定时同步 VM 数据到 MongoDB（每 3 小时），同步任务可能突增 CPU
- **kubelet/kube-proxy**：节点系统组件，高 CPU 影响 Pod 调度与网络转发
- **业务应用进程**：Java/Go/Python 应用进程，可能是 CPU 高负载的直接原因

## 排障步骤

1. **top 命令定位高 CPU 进程**：SSH 登录目标节点（若 SSH 卡顿可通过云控制台 VNC 登录），执行 `top -bn1 | head -20` 查看 CPU 使用率最高的进程。重点关注 `%CPU` 列，记录进程 PID、命令名和 CPU 占用百分比。若 Java 进程占 CPU 最高，记录 PID 用于后续线程栈分析。
2. **查 7 天 CPU 趋势**：通过 VM `/v2/oneAgg` 接口查询近 7 天 CPU 使用率时序，判断是突发尖峰还是缓慢爬升。PromQL：`1 - avg(rate(node_cpu_seconds_total{mode="idle",instance="$host"}[5m]))`。若 7 天趋势显示 CPU 持续上升，可能是数据增长或查询复杂度累积。
3. **关联资源分析**：通过 VM `/v2/commonQuery` 查询同一节点的内存使用率、磁盘 I/O、网络 IOPS，排除内存不足导致频繁 swap 引发的 CPU 升高、磁盘 I/O 瓶颈导致进程阻塞等待。PromQL：`node_memory_MemAvailable_bytes{instance="$host"} / node_memory_MemTotal_bytes{instance="$host"}`。
4. **查业务日志**：检查高 CPU 进程对应的业务日志，查看是否有异常 Error 日志、大量重复请求、死循环或频繁 GC。Java 进程执行 `jstack <pid>` 导出线程栈，分析线程状态分布（RUNNABLE/BLOCKED/WAITING）。Go 进程通过 pprof 获取 CPU profile。
5. **检查关联资源负载**：通过 VM 查询同一节点上其他 Pod 和进程的 CPU 使用率，排查是否存在资源争抢。PromQL：`topk(10, rate(container_cpu_usage_seconds_total{node="$node"}[5m]))`。若多个 Pod 同时高 CPU，可能是节点规格不足。
6. **检查系统进程**：执行 `top -bn1` 检查 `kworker`、`ksoftirqd`、`migration` 等内核线程 CPU 占用，若内核线程 CPU 偏高可能是网络包处理、中断处理或内存回收导致。

## 关键 PromQL

```promql
# 1. 节点 CPU 使用率——核心指标
# node_cpu_seconds_total{mode="idle"}：节点空闲 CPU 秒数，1 减去空闲率即为使用率
1 - avg(rate(node_cpu_seconds_total{mode="idle",instance="$host"}[5m]))

# 2. 节点 load15 与核数比——反映 CPU 排队压力
# node_load15：15 分钟平均负载，除以核数得到每核平均排队数
node_load15{instance="$host"} / on(instance) count by (instance) (node_cpu_seconds_total{mode="idle",instance="$host"})

# 3. 进程级 CPU 消耗——定位具体高 CPU 进程
topk(10, rate(process_cpu_seconds_total{instance="$host"}[5m]))

# 4. Pod 级 CPU 消耗——定位高 CPU Pod
topk(10, rate(container_cpu_usage_seconds_total{node="$node"}[5m]))

# 5. CPU 使用率 7 天趋势——判断是突发还是持续恶化
deriv(1 - avg(rate(node_cpu_seconds_total{mode="idle",instance="$host"}[5m]))[7d])

# 6. 节点内存可用率——排查内存不足导致 swap
node_memory_MemAvailable_bytes{instance="$host"} / node_memory_MemTotal_bytes{instance="$host"}

# 7. 磁盘 I/O 等待——排查 I/O 瓶颈导致 CPU 等待
avg(rate(node_cpu_seconds_total{mode="iowait",instance="$host"}[5m]))
```

## 真实案例

**时间线**：2024 年 7 月，联通数科 MSP 生产环境 POC2 集群。

**T+0min**：POC2 节点 `ecs-poc2-03`（8 核 32GB ECS 实例）CPU 使用率持续超过 92%，P1 告警触发，钉钉告警卡片推送至值班群。该节点承载 cspm-ms-monitorservice 和 dispatch 网关两个关键服务。

**T+3min**：值班人员通过 VM `/v2/oneAgg` 查询 7 天 CPU 时序，确认 CPU 使用率从 3 天前的 45% 缓慢爬升至当前 92%，呈持续上升趋势而非突发尖峰。load15/核数比达到 3.2，表示每个核平均有 3.2 个进程排队。

**T+8min**：SSH 登录节点（登录延迟约 15 秒），执行 `top -bn1` 发现 Java 进程 `cspm-ms-monitorservice`（PID 12847）占用 CPU 380%（约 3.8 核），`dispatch` 进程（PID 8923）占用 CPU 120%（约 1.2 核），两个进程合计占用 5 核。

**T+12min**：对 `cspm-ms-monitorservice` 执行 `jstack 12847` 导出线程栈，发现 52 个线程状态为 RUNNABLE，堆栈均指向 `PromQLQueryEngine.executeAggregation()` 方法。进一步排查业务日志，发现大量 `/v2/oneAgg` 接口调用，单次请求对 200+ 指标全量扫描耗时超过 30 秒。

**T+18min**：通过 VM `/v2/commonQuery` 查询同一节点的内存使用率 78%（正常）、磁盘 I/O wait 2%（正常）、网络 IOPS 正常，排除内存和 I/O 瓶颈。确认 CPU 高负载完全由 PromQL 全量扫描计算导致。

**T+25min**：检查 `dispatch` 进程 CPU 占用 120% 的原因，发现由于 `cspm-ms-monitorservice` 大量调用 `/v1/query/complex` 接口，dispatch 网关处理高并发查询请求消耗大量 CPU。

**根因**：（1）`cspm-ms-monitorservice` 对 10 类产品（vm/phy/bms/ECS/aicp-notebook/aicp-job/aicp-isvc/netDevice/aicc/eip）的 200+ 指标执行全量扫描，未加时间范围过滤，PromQL 聚合计算消耗大量 CPU；（2）dispatch 网关处理高并发查询请求 CPU 同步升高；（3）3 天前新增了定时报表任务，每 10 分钟触发一次全量扫描，导致 CPU 从 45% 缓慢爬升。

**处置**：（1）紧急停止新增的定时报表任务，CPU 使用率于 T+30min 回落至 65%；（2）对 `/v2/oneAgg` 和 `/v1/query/complex` 接口增加强制时间范围参数，默认限制最近 1 小时；（3）`cspm-ms-monitorservice` 增加查询结果缓存，相同查询 5 分钟内返回缓存结果；（4）dispatch 网关扩容至 2 个副本分担查询压力。CPU 使用率于 T+45min 回落至 40%。

## 自动分诊流程

AI Agent 接收 `ECSHostCPUHigh` 告警后执行以下自动化分诊：

1. **告警解析**：提取 `instance` 标签，判定节点名称、产品类型（ECS/EBM/PHY）和告警级别（P0-P3），关联 CMDB 确认节点承载的业务系统。
2. **进程定位**：通过 SSH 执行 `top -bn1` 获取 Top10 高 CPU 进程列表，识别 CPU 占用最高的进程 PID 和命令名，判断是业务进程还是系统进程。
3. **趋势分析**：调用 VM `/v2/oneAgg` 查询 7 天 CPU 使用率 `deriv` 值，判断是突发尖峰（deriv 短期突增）还是缓慢爬升（deriv 持续为正），匹配历史告警模式。
4. **关联分析**：并行查询内存使用率、磁盘 I/O wait、网络 IOPS，排除内存不足、I/O 瓶颈等外部因素。若内存可用率 <10% 且 swap 使用率高，判定为内存不足导致 swap 引发 CPU 升高。
5. **线程栈分析**：对高 CPU Java 进程自动执行 `jstack` 导出线程栈，通过 NLP 分析线程状态分布和热点方法，定位代码级瓶颈。
6. **处置建议**：生成处置卡片推送钉钉群，包含进程级 CPU 分布、根因分析、扩容命令和代码优化建议，等待值班人员确认执行。

## 处置建议

1. 临时通过 `kubectl scale deployment <name> --replicas=N` 扩容高 CPU 服务的 Pod 数量，分散请求压力。
2. 对 CPU 密集型查询接口增加强制时间范围参数，默认限制最近 1 小时，避免全量扫描。
3. 查询结果增加缓存层（Redis 或本地缓存），相同查询 5 分钟内返回缓存结果，降低重复计算开销。
4. 若节点 CPU 长期高位且无法通过优化降低，考虑升级节点规格（增加核数）或将部分工作负载迁移至其他节点。
5. 对异常高 CPU 且无业务价值的进程（如僵尸进程、失控的定时任务），通过 `kill -15 <pid>` 优雅终止。
6. 配置 HPA 自动伸缩策略，基于 CPU 使用率阈值（70% 扩容、50% 缩容）自动扩缩容。

## 预防措施

1. **查询接口治理**：所有 VM 查询接口强制要求 `time_range` 参数，未传则默认 1 小时，超过 24 小时需审批。
2. **PromQL 复杂度审计**：定期扫描 `/v2/oneAgg` 和 `/v1/query/complex` 接口的查询语句，识别高成本查询并优化，单次查询指标数不超过 50。
3. **HPA 全覆盖**：所有 msp-prod 命名空间服务配置 HPA，CPU 阈值 70% 自动扩容。
4. **资源配额基线**：根据 200+ 指标的采集频率和数据量，为每类产品（vm/phy/bms/ECS 等 10 类）设定 CPU request/limit 基线值。
5. **巡检报表**：XXL-JOB 每 3 小时同步 VM CPU 指标至 MongoDB，生成 CPU 使用趋势报表，识别持续高位运行的服务和节点。

## 相关 PromQL 速查

```promql
# 节点 CPU 使用率
1 - avg(rate(node_cpu_seconds_total{mode="idle",instance="$host"}[5m]))

# 节点 load15 与核数比
node_load15{instance="$host"} / on(instance) count by (instance) (node_cpu_seconds_total{mode="idle",instance="$host"})

# 进程级 CPU Top10
topk(10, rate(process_cpu_seconds_total{instance="$host"}[5m]))

# Pod 级 CPU Top10
topk(10, rate(container_cpu_usage_seconds_total{node="$node"}[5m]))

# CPU 7 天趋势
deriv(1 - avg(rate(node_cpu_seconds_total{mode="idle",instance="$host"}[5m]))[7d])

# 内存可用率
node_memory_MemAvailable_bytes{instance="$host"} / node_memory_MemTotal_bytes{instance="$host"}

# 磁盘 I/O wait
avg(rate(node_cpu_seconds_total{mode="iowait",instance="$host"}[5m]))

# 节点 CPU 负载均衡度
stddev(rate(container_cpu_usage_seconds_total[5m])) by (node)
```
