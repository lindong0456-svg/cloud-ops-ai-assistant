# CPU 持续高负载告警处理

## 告警规则

告警名称：`CPUUsageHigh`

PromQL 表达式：

```promql
# Pod 维度 CPU 使用率（核心告警）
rate(container_cpu_usage_seconds_total{pod=~"msp-.*",namespace="msp-prod"}[5m]) > 0.8

# 节点维度 CPU 使用率（辅助告警）
1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) by (instance) > 0.85
```

指标说明：`container_cpu_usage_seconds_total` 是 cAdvisor 采集的容器 CPU 累计使用时间（秒），`rate(...[5m])` 计算 5 分钟内的平均增长率即 CPU 使用率。`pod` 标签匹配 `msp-` 前缀的服务 Pod，`namespace` 限定在 `msp-prod`。节点维度使用 `node_cpu_seconds_total{mode="idle"}` 的空闲时间反推使用率。

触发条件：CPU 使用率超过 80% 且持续 5 分钟以上。该告警覆盖 vm、phy、bms、ECS、aicp-notebook、aicp-job、aicp-isvc、netDevice、aicc、eip 共 10 类产品线，通过 dispatch（端口 9091）→ cspm → adapter 架构采集，数据写入 VictoriaMetrics。

## 告警分级

| 级别 | 阈值条件 | 响应时效 | 通知方式 |
|------|---------|---------|---------|
| P0 | CPU > 95% 持续 10min 且节点负载 > 8 | 5 分钟内 | 电话 + 钉钉 + 短信 |
| P1 | CPU > 90% 持续 5min 且影响业务接口可用性 | 15 分钟内 | 电话 + 钉钉告警卡片 |
| P2 | CPU > 80% 持续 5min | 30 分钟内 | 钉钉群机器人推送 |
| P3 | CPU > 70% 持续 15min（趋势预警） | 巡检处理 | 钉钉群消息 |

## 影响评估

CPU 持续高负载将导致：服务接口响应时间劣化（P99 延迟升高）、请求队列堆积引发超时雪崩、Kubernetes 触发 Pod 驱逐、HPA 扩容不及时导致服务降级。若发生在 dispatch 网关节点（端口 9091），将影响全平台指标采集链路；若发生在 cspm 节点，将影响配置下发与策略执行。

## 关联组件

- **dispatch（端口 9091）**：指标采集网关，CPU 飙升将导致采集延迟和指标丢失
- **cspm**：云安全策略管理，CPU 高负载影响策略下发实时性
- **adapter**：协议适配层，连接 VM 与上层 API，CPU 瓶颈导致查询超时
- **VictoriaMetrics**：时序数据库，查询聚合消耗 CPU，复杂 PromQL 加剧负载
- **XXL-JOB**：定时同步 VM 数据到 MongoDB（每 3 小时），同步任务可能突增 CPU
- **kubelet/kube-proxy**：节点系统组件，高 CPU 影响 Pod 调度与网络转发

## 排障步骤

1. **确认告警 Pod 与产品线**：登录 AlertManager 查看 `pod`、`namespace` 标签，通过 VM 查询接口 `/v1/query/complex` 传入模板变量确认产品类型（vm/phy/bms/ECS/aicp-notebook 等 10 类），记录 Pod 名称和所在节点。
2. **查 CPU 时序走势**：通过 VictoriaMetrics `/v2/oneAgg` 接口查询近 30 分钟 CPU 走势，对比同一 Deployment 其他 Pod 的 CPU 水平，确认是单 Pod 异常还是整体集群偏高。PromQL：`rate(container_cpu_usage_seconds_total{pod="$pod"}[5m])`。
3. **查 Pod 日志**：执行 `kubectl logs -n msp-prod <pod-name> --tail=200`，查看是否有异常 Error 日志、OOM 前兆或大量重复请求堆积。
4. **查应用线程栈**：进入容器执行 `jstack <pid>` 导出线程栈，定位是否存在死循环、大量阻塞线程或频繁 GC。对 Go 服务执行 `curl http://localhost:6060/debug/pprof/profile?seconds=30` 获取 CPU profile。
5. **关联指标交叉分析**：通过 `/v2/commonQuery`（MongoDB 风格查询转 PromQL）查询同一 Pod 的网络 IOPS、磁盘 I/O、内存使用率，结合 200+ 指标矩阵排除外部因素干扰。重点检查是否存在 `/v2/oneAgg` 接口全量扫描 200+ 指标导致 CPU 飙升。
6. **查节点资源争抢**：通过 `1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) by (instance)` 确认节点整体负载，排查是否存在同节点其他 Pod 争抢 CPU 资源。

## 关键 PromQL

```promql
# 1. CPU 使用率（Pod 维度）——核心指标
# container_cpu_usage_seconds_total：容器 CPU 累计使用秒数，rate 计算 5 分钟均值
rate(container_cpu_usage_seconds_total{pod="$pod",namespace="$ns"}[5m])

# 2. CPU 使用率（节点维度）——排查节点级瓶颈
# node_cpu_seconds_total{mode="idle"}：节点空闲 CPU 秒数，1 减去空闲率即为使用率
1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) by (instance)

# 3. CPU 限制使用率（相对 limit）——判断是否触顶 cgroup 限制
# container_spec_cpu_quota：cgroup 设置的 CPU 配额，除以 100000 得到核数
rate(container_cpu_usage_seconds_total{pod="$pod"}[5m]) / on(pod) (container_spec_cpu_quota / 100000)

# 4. CPU 负载趋势（1 小时环比）——判断是否持续恶化
# deriv 计算 1 小时内变化率，正值表示上升趋势
deriv(rate(container_cpu_usage_seconds_total{pod="$pod"}[5m])[1h])

# 5. 同节点 Pod CPU 排序——定位资源争抢
topk(5, rate(container_cpu_usage_seconds_total{node="$node"}[5m]))
```

## 真实案例

**时间线**：2024 年 3 月，联通数科 MSP 生产环境 POC2 集群。

**T+0min**：`cspm-ms-monitorservice` Pod CPU 持续飙升至 90%，P1 告警触发，钉钉告警卡片推送至值班群。

**T+5min**：值班人员通过 VM `/v2/oneAgg` 接口查询 30 分钟 CPU 时序，确认 CPU 从 60% 在 3 分钟内跳升至 90%，呈突发尖峰后高位维持。

**T+10min**：执行 `kubectl logs` 查看 Pod 日志，发现大量 `/v2/oneAgg` 和 `/v2/commonQuery` 接口调用日志，单次请求耗时超过 30 秒。

**T+15min**：进入容器执行 `jstack` 导出线程栈，发现 48 个线程同时执行 PromQL 聚合计算，线程状态均为 RUNNABLE。

**T+20min**：通过 `/v2/commonQuery` 接口排查，确认该服务对 200+ 指标全量扫描未加时间范围过滤，每次请求拉取全量时序数据。`/v2/commonQuery` 将 MongoDB 风格查询转换为 PromQL 时未做查询复杂度限制。

**根因**：cspm-ms-monitorservice 调用 VM 查询接口时，对 10 类产品（vm/phy/bms/ECS/aicp-notebook/aicp-job/aicp-isvc/netDevice/aicc/eip）的 200+ 指标执行全量扫描，未加时间范围过滤，导致每次请求都拉取全量时序数据，CPU 长期满载。

**处置**：（1）紧急通过 `kubectl scale deployment cspm-ms-monitorservice --replicas=3` 扩容缓解压力；（2）查询接口增加强制时间范围参数，默认限制最近 1 小时；（3）`/v2/commonQuery` 接口增加查询复杂度限制，单次查询指标数不超过 50；（4）对 200+ 指标按产品类型建立索引缓存，避免重复全量扫描。CPU 于 T+45min 回落至 45%。

## 自动分诊流程

AI Agent 接收 `CPUUsageHigh` 告警后执行以下自动化分诊：

1. **告警解析**：提取 `pod`、`namespace`、`instance` 标签，判定产品类型（10 类产品之一）和告警级别（P0-P3）。
2. **指标采集**：通过 VM `/v1/query/complex` 接口并行查询 CPU 时序、内存使用率、网络 IOPS、磁盘 I/O，构建 200+ 指标关联视图。
3. **趋势分析**：调用 `/v2/oneAgg` 计算 CPU 1 小时 `deriv` 值，判断是突发尖峰还是缓慢爬升，匹配历史告警模式。
4. **日志关联**：自动拉取 Pod 最近 200 行日志，通过 NLP 提取 Error/Warn 关键字，关联 CPU 飙升时间点。
5. **根因推断**：基于规则引擎匹配常见模式——全量扫描（查询接口无时间范围）、GC 风暴（jvm_gc 频率异常）、流量激增（QPS 突增）、资源争抢（同节点 Pod CPU 偏高）。
6. **处置建议**：生成处置卡片推送钉钉群，包含扩容命令、查询优化建议、HPA 配置参数，等待值班人员确认执行。

## 处置建议

1. 查询接口增加强制时间范围参数，默认限制最近 1 小时，避免全量扫描。
2. 聚合查询结果分页返回，单页不超过 500 条，降低单次查询 CPU 开销。
3. 临时通过 `kubectl scale deployment <name> --replicas=3` 扩容缓解压力。
4. 长期优化：对 200+ 指标按产品类型（vm/phy/bms/ECS 等 10 类）建立索引缓存，避免重复全量扫描。
5. 配置 HPA 自动伸缩策略，基于 CPU 使用率阈值（70% 扩容、50% 缩容）自动扩缩容。
6. 对 `/v2/commonQuery` 接口增加查询复杂度限制，禁止无过滤条件的全量指标查询。

## 预防措施

1. **查询接口治理**：所有 VM 查询接口强制要求 `time_range` 参数，未传则默认 1 小时，超过 24 小时需审批。
2. **PromQL 复杂度审计**：定期扫描 `/v2/oneAgg` 和 `/v1/query/complex` 接口的查询语句，识别高成本查询并优化。
3. **HPA 全覆盖**：所有 msp-prod 命名空间服务配置 HPA，CPU 阈值 70% 自动扩容。
4. **资源配额基线**：根据 200+ 指标的采集频率和数据量，为每类产品设定 CPU request/limit 基线值。
5. **巡检报表**：XXL-JOB 每 3 小时同步 VM CPU 指标至 MongoDB，生成 CPU 使用趋势报表，识别持续高位运行的服务。

## 相关 PromQL 速查

```promql
# Pod CPU 使用率
rate(container_cpu_usage_seconds_total{pod="$pod",namespace="$ns"}[5m])

# 节点 CPU 使用率
1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) by (instance)

# CPU 相对 limit 使用率
rate(container_cpu_usage_seconds_total{pod="$pod"}[5m]) / on(pod) (container_spec_cpu_quota / 100000)

# CPU 1 小时趋势
deriv(rate(container_cpu_usage_seconds_total{pod="$pod"}[5m])[1h])

# 同节点 CPU Top5 Pod
topk(5, rate(container_cpu_usage_seconds_total{node="$node"}[5m]))

# CPU 节点负载均衡度
stddev(rate(container_cpu_usage_seconds_total[5m])) by (node)

# 容器 CPU throttling 次数
rate(container_cpu_cfs_throttled_periods_total[5m])

# 节点 load15 与核数比
node_load15 / on(instance) count by (instance) (node_cpu_seconds_total{mode="idle"})
```
