# 内存泄漏告警处理

## 告警规则

告警名称：`MemoryLeakSuspected`

PromQL 表达式：

```promql
# 内存持续上升趋势检测（核心告警）
deriv(container_memory_working_set_bytes{pod=~"msp-.*",namespace="msp-prod"}[24h]) > 0

# 内存使用率超 limit 90%（升级条件）
container_memory_working_set_bytes{pod="$pod"} / on(pod) container_spec_memory_limit_bytes > 0.9

# GC 频率异常（辅助条件）
rate(jvm_gc_collection_seconds_count{pod="$pod"}[5m]) > 5
```

指标说明：`container_memory_working_set_bytes` 是 cAdvisor 采集的容器实际使用内存（含常驻内存），`deriv(...[24h])` 计算 24 小时变化率，正值表示持续上升即泄漏趋势。`container_spec_memory_limit_bytes` 是 cgroup 内存限制，使用量除以限制即为使用率。`jvm_gc_collection_seconds_count` 是 JVM GC 次数累计值，`rate` 计算 5 分钟内 GC 频率，频率过高暗示内存压力导致频繁回收。

触发条件：Pod 内存使用量 24 小时内持续上升（P2），使用率超 limit 90% 升级为 P1。该告警主要针对 Java 服务（如 dispatch 网关端口 9091）及长期运行的 Go 服务。数据通过 VM 采集，XXL-JOB 每 3 小时同步至 MongoDB 生成内存趋势报表。

## 告警分级

| 级别 | 阈值条件 | 响应时效 | 通知方式 |
|------|---------|---------|---------|
| P0 | 内存使用率 > 95% 且持续 OOMKill | 5 分钟内 | 电话 + 钉钉 + 短信 |
| P1 | 内存使用率 > 90% limit 或 GC 频率 > 10/min | 15 分钟内 | 电话 + 钉钉告警卡片 |
| P2 | 24h 内内存持续上升趋势（deriv > 0） | 30 分钟内 | 钉钉群机器人推送 |
| P3 | 内存使用率 > 75% limit（趋势预警） | 巡检处理 | 钉钉群消息 |

## 影响评估

内存泄漏将导致：Pod 频繁 OOMKill 触发重启、服务中断期间请求失败、JVM 频繁 Full GC 导致 STW 停顿、级联雪崩影响依赖服务。若发生在 dispatch 网关（端口 9091），指标采集链路中断；若发生在 cspm/adapter，配置下发和查询适配中断。OOMKill 后 Pod 重启虽可恢复，但内存泄漏根因未消除将持续循环。

## 关联组件

- **dispatch（端口 9091）**：Java 网关服务，Nacos 配置监听器泄漏的高发组件
- **cspm**：Java 服务，配置管理模块可能存在监听器泄漏
- **adapter**：Java/Go 服务，协议转换中可能存在缓冲区未释放
- **VictoriaMetrics**：查询结果缓存可能导致内存占用增长
- **Nacos**：配置中心，心跳周期（3 小时）与配置刷新触发泄漏
- **kubelet**：监控 Pod 内存使用量，超 limit 触发 OOMKill
- **JVM**：堆/非堆内存管理，GC 策略影响内存回收效率

## 排障步骤

1. **查内存时序确认趋势**：通过 VM `/v1/query/complex` 接口传入内存时序 PromQL 模板，查询近 72 小时内存走势。PromQL：`container_memory_working_set_bytes{pod="$pod"}`。确认是否存在阶梯式或线性增长模式，排除正常业务波峰导致的误判。对比同 Deployment 其他 Pod 的内存水平。
2. **查 JVM 堆/非堆使用**：通过 `/v2/oneAgg` 查询 `jvm_memory_used_bytes{pod="$pod",area="heap"}` 和 `area="nonheap"`，区分堆内/堆外内存泄漏。同时查询 GC 频率：`rate(jvm_gc_collection_seconds_count[5m])`，关注 Full GC 是否异常升高。
3. **导出 heap dump**：进入容器执行 `jmap -dump:format=b,file=/tmp/heap.bin <pid>`，或通过 JVM 参数 `-XX:+HeapDumpOnOutOfMemoryError` 在 OOM 时自动导出。若容器即将 OOM，优先导出 dump 再重启。
4. **分析泄漏对象**：使用 MAT（Memory Analyzer Tool）加载 heap dump，查看 Dominator Tree 定位大对象，检查 GC Root 引用链，确认对象无法回收的根因。重点关注 Listener、Cache、ThreadLocal 等常见泄漏源。
5. **关联事件分析**：查看内存跳涨时间点是否与定时任务、配置变更等周期性事件相关。对比 Nacos 配置刷新时间、XXL-JOB 任务执行时间（每 3 小时同步 VM 到 MongoDB），确认泄漏周期与外部触发频率是否吻合。
6. **查容器 OOM 历史**：执行 `kubectl describe pod <pod>` 查看 `Last State: Terminated` 中的 `Reason: OOMKilled` 记录，统计 OOM 发生频率和每次重启后的内存增长速度。

## 关键 PromQL

```promql
# 1. Pod 内存使用量——核心指标
# container_memory_working_set_bytes：容器实际使用内存（含不可回收部分）
container_memory_working_set_bytes{pod="$pod",namespace="$ns"}

# 2. 内存使用率（相对 limit）——判断 OOM 风险
# container_spec_memory_limit_bytes：cgroup 内存限制
container_memory_working_set_bytes{pod="$pod"} / on(pod) container_spec_memory_limit_bytes

# 3. 24h 内存趋势——泄漏检测核心
# deriv 计算 24 小时变化率，正值表示上升趋势
deriv(container_memory_working_set_bytes{pod="$pod"}[24h])

# 4. JVM 堆内存使用——区分堆/非堆泄漏
# area="heap" 为堆内内存，area="nonheap" 为堆外内存（Metaspace/DirectBuffer）
jvm_memory_used_bytes{pod="$pod",area="heap"}

# 5. JVM GC 频率——辅助判断内存压力
# jvm_gc_collection_seconds_count：GC 累计次数，rate 计算 5 分钟频率
rate(jvm_gc_collection_seconds_count{pod="$pod"}[5m])

# 6. RSS 内存（进程实际驻留内存）——排查堆外泄漏
container_memory_rss{pod="$pod"}

# 7. OOMKill 计数——统计重启频率
increase(container_oom_events_total{pod="$pod"}[24h])
```

## 真实案例

**时间线**：2024 年 4 月，联通数科 MSP 生产环境，dispatch 网关（端口 9091）。

**T+0min**：dispatch Pod 内存使用率达 88%，P2 告警触发（24h deriv > 0），钉钉群推送告警卡片。

**T+10min**：值班人员通过 VM `/v2/oneAgg` 接口查询 72 小时内存时序，确认内存每 3 小时上涨约 200MB，呈典型阶梯式泄漏模式。72 小时内从 1.2GB 增长至 2.8GB（limit 3GB）。

**T+20min**：查询 `jvm_memory_used_bytes{area="heap"}` 确认堆内存同步增长，`rate(jvm_gc_collection_seconds_count[5m])` 显示 Full GC 频率从 0.2/min 升至 3.5/min，内存压力明显。

**T+30min**：进入容器执行 `jmap -dump:format=b,file=/tmp/heap.bin <pid>` 导出 heap dump（1.8GB），通过 `kubectl cp` 传至分析机。

**T+40min**：MAT 分析 heap dump，Dominator Tree 显示 `ConfigurationListener` 实例数高达 800+，每个实例持有约 2MB 配置数据。GC Root 引用链显示监听器被 Nacos 的 `EventDispatcher` 内部 List 引用，无法回收。

**T+50min**：对比内存跳涨时间点与 Nacos 心跳周期（3 小时），确认每次 Nacos 配置刷新（心跳 3 小时）都创建新的 `ConfigurationListener` 且未注销旧监听器，泄漏周期与 Nacos 心跳完全吻合。

**根因**：dispatch 网关的 Nacos 配置变更监听器未正确管理生命周期。每次配置刷新回调中创建新的 `ConfigurationListener` 对象并注册到 `EventDispatcher`，但未注销旧监听器，导致监听器对象无限堆积。3 小时心跳周期导致每次跳涨约 200MB，不处理将在数天内触发 OOMKill。

**处置**：（1）修复代码：配置刷新回调中先调用 `eventDispatcher.removeListener(oldListener)` 注销旧监听器，再注册新监听器；（2）JVM 参数优化：`-XX:MaxRAMPercentage=75` 防止容器内存超限；（3）配置 Liveness Probe 内存阈值自动重启作为兜底；（4）部署后内存稳定在 1.2GB，72 小时无增长趋势。

## 自动分诊流程

AI Agent 接收 `MemoryLeakSuspected` 告警后执行以下自动化分诊：

1. **告警解析**：提取 `pod`、`namespace` 标签，判定服务类型（Java/Go）和告警级别。
2. **趋势分析**：通过 VM `/v2/oneAgg` 查询 72 小时内存时序，计算 `deriv(...[24h])` 和 `deriv(...[72h])`，判断泄漏模式（阶梯式/线性/突发）。
3. **周期匹配**：将内存跳涨时间点与 Nacos 心跳周期（3 小时）、XXL-JOB 同步周期（3 小时）进行关联分析，识别周期性触发源。
4. **GC 诊断**：查询 `jvm_gc_collection_seconds_count` 和 `jvm_memory_used_bytes{area="heap/nonheap"}`，区分堆内/堆外泄漏，评估 GC 压力。
5. **OOM 历史**：查询 `increase(container_oom_events_total[24h])` 统计 OOMKill 次数，若 > 3 次升级为 P0 紧急。
6. **根因推断**：基于规则引擎匹配常见泄漏模式——Nacos 监听器泄漏（阶梯式 + 3h 周期）、DirectBuffer 泄漏（非堆增长）、ThreadLocal 泄漏（线程数增长）、缓存无限膨胀（线性增长）。
7. **处置建议**：生成处置卡片推送钉钉群，包含 dump 导出命令、代码修复建议、JVM 参数优化方案，等待值班人员确认。

## 处置建议

1. 修复代码：配置刷新回调中先注销旧监听器再注册新监听器，确保监听器生命周期可控。
2. JVM 参数优化：设置 `-XX:MaxRAMPercentage=75` 防止容器内存超限被 OOMKill。
3. 配置 Liveness Probe：设置内存使用率阈值自动重启，作为兜底防线。
4. 告警巡检：每日通过 XXL-JOB 定时任务调用 VM 查询接口检测内存趋势，3 小时同步至 MongoDB 生成内存报表。
5. 压测回归：上线前进行 48 小时稳定性压测，监控内存趋势是否平稳。
6. 对 dispatch/cspm/adapter 等 Java 服务统一配置 `-XX:+HeapDumpOnOutOfMemoryError` 自动导出 dump。

## 预防措施

1. **监听器生命周期审计**：所有 Nacos 配置监听器、事件订阅器必须实现 `AutoCloseable`，在 `close()` 中注销注册。
2. **内存基线监控**：XXL-JOB 每 3 小时同步 VM 内存指标至 MongoDB，建立各服务 72 小时内存基线，`deriv` 超阈值自动告警。
3. **JVM 参数标准化**：所有 Java 服务统一配置 `-XX:MaxRAMPercentage=75 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.bin`。
4. **稳定性压测门禁**：上线前 48 小时压测，内存增长速率不超过 50MB/24h 方可发布。
5. **OOM 兜底**：所有关键服务配置 Liveness Probe 内存阈值自动重启，避免级联雪崩。

## 相关 PromQL 速查

```promql
# Pod 内存使用量
container_memory_working_set_bytes{pod="$pod",namespace="$ns"}

# 内存使用率（相对 limit）
container_memory_working_set_bytes{pod="$pod"} / on(pod) container_spec_memory_limit_bytes

# 24h 内存趋势
deriv(container_memory_working_set_bytes{pod="$pod"}[24h])

# JVM 堆内存
jvm_memory_used_bytes{pod="$pod",area="heap"}

# JVM 非堆内存
jvm_memory_used_bytes{pod="$pod",area="nonheap"}

# JVM GC 频率
rate(jvm_gc_collection_seconds_count{pod="$pod"}[5m])

# RSS 内存（堆外泄漏排查）
container_memory_rss{pod="$pod"}

# 24h OOMKill 次数
increase(container_oom_events_total{pod="$pod"}[24h])

# 同 Deployment Pod 内存对比
container_memory_working_set_bytes{deployment="$dep"} / on(pod) container_spec_memory_limit_bytes
```
