# K8s OOMKilled 排障

## 故障现象

在 msp-prod 命名空间中，Pod 运行一段时间后突然终止，`kubectl get pods` 显示 RESTARTS 计数增加。`kubectl describe pod` 中 Last State 的 Reason 为 OOMKilled，Exit Code 为 137。服务出现间歇性不可用。典型表现：

```
NAME                              READY   STATUS    RESTARTS   AGE
cspm-ms-monitorservice-8a9b0c1   0/1     Running   6          45m
```

```
Containers:
  cspm-ms-monitorservice:
    Last State:     Terminated
      Reason:       OOMKilled
      Exit Code:    137
      Started:      Mon, 15 Jan 2024 13:50:00
      Finished:     Mon, 15 Jan 2024 14:15:00
    Restart Count:  6
```

dispatch 网关（9091 端口）因后端 Pod 被杀后短暂不可用，收到 503 错误，用户请求间歇性失败。

## 影响评估

- **直接影响**：OOMKilled 后 Pod 被杀死并重启，重启期间（通常 10-30 秒）服务不可用。频繁 OOM 导致服务处于"运行-崩溃-重启"循环，可用性严重下降。
- **连锁反应**：cspm 的 monitorservice 负责 200+ 监控指标采集，OOMKilled 后指标采集中断，VictoriaMetrics 出现数据断点，告警规则可能误触发或漏触发。
- **数据风险**：OOMKilled 是强制终止（SIGKILL），应用无法执行优雅关闭，可能导致正在处理的请求丢失、文件写入不完整。
- **波及范围**：若多个 cspm 副本同时 OOMKilled（如全集群内存压力），dispatch 网关后端无可用 Pod，三层调用链完全中断，影响所有政务云项目的云资源管理。
- **业务等级**：P2 级故障（单副本 OOM），若多副本同时 OOM 升级为 P1。

## 关联组件

| 组件 | 说明 |
|------|------|
| Linux OOM Killer | 内核机制，节点内存不足时按 oom_score 杀死进程 |
| kubelet | 检测容器退出码 137，按重启策略重启 Pod |
| JVM (Java) | cspm 和部分 adapter 服务为 Java 应用，堆/非堆内存配置直接影响 OOM |
| cgroups | 容器内存限制通过 cgroups 实现，超过 limit 触发 OOM |
| VictoriaMetrics | 监控平台，依赖 monitorservice 上报指标，OOM 后采集中断 |
| Helm | values.yaml 中 resources.limits.memory 控制 Pod 内存上限 |

## 常见原因

1. **容器内存 limit 过低**：resources.limits.memory 设置不足以支撑应用正常运行。cspm 的 monitorservice 采集 200+ 指标，内存需求较大。
2. **应用内存泄漏**：代码中存在未释放的对象引用、连接泄漏或缓存无限增长，内存持续增长直到触达 limit。
3. **JVM 堆配置不当**：Java 服务使用 `-Xmx` 硬编码且等于容器 limit，未给非堆内存（Metaspace、线程栈、直接内存、JIT 代码缓存）留空间。或未使用 `-XX:MaxRAMPercentage` 导致 JVM 按宿主机内存分配堆。
4. **突发流量峰值**：高并发场景下瞬时内存分配超过 limit 被内核 OOM Killer 杀死。政务云项目集中巡检时段易发。
5. **内存限制与节点压力叠加**：容器 limit 合理但节点整体内存不足，内核 OOM Killer 优先杀死 oom_score 高的进程。

## 排障步骤

**步骤 1：确认 OOMKilled 状态与退出码**

```bash
kubectl describe pod <pod-name> -n msp-prod
```

重点查看 Containers 区域的 Last State：

```
Containers:
  cspm-ms-monitorservice:
    State:          Running
    Last State:     Terminated
      Reason:       OOMKilled
      Exit Code:    137
      Started:      Mon, 15 Jan 2024 13:50:00
      Finished:     Mon, 15 Jan 2024 14:15:00
    Restart Count:  6
```

Exit Code 137 = 128 + 9 (SIGKILL)，Reason 为 OOMKilled 即确认容器因内存超限被杀。

**步骤 2：查看崩溃前日志**

```bash
kubectl logs <pod-name> -n msp-prod --previous | grep -i "OutOfMemory\|OOM\|heap"
```

期望输出（Java 应用）：
```
java.lang.OutOfMemoryError: Java heap space
    at com.unicom.msp.cspm.monitor.CollectorService.batchCollect(CollectorService.java:128)
    at com.unicom.msp.cspm.monitor.MonitorScheduler.run(MonitorScheduler.java:65)
```

若无 OutOfMemoryError 但仍 OOMKilled，可能是非堆内存（Metaspace、直接内存）超限，或被节点级 OOM Killer 杀死。

**步骤 3：检查当前内存配置**

```bash
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.spec.containers[0].resources}'
```

期望输出：
```json
{"limits":{"cpu":"2","memory":"2Gi"},"requests":{"cpu":"500m","memory":"1Gi"}}
```

对比 limits.memory 与应用实际内存需求，判断是否过低。

**步骤 4：检查 JVM 启动参数**

```bash
# 查看 Java 进程启动参数
kubectl exec -it <pod-name> -n msp-prod -- jcmd 1 VM.flags | grep -E "MaxHeap|MaxRAMPercentage"

# 或查看环境变量
kubectl exec -it <pod-name> -n msp-prod -- env | grep JAVA_OPTS
```

期望输出：
```
-XX:MaxRAMPercentage=60
```

若输出为 `-Xmx2048m` 且等于 limits.memory，说明未给非堆内存留空间。

**步骤 5：分析内存使用趋势**

```bash
# 查看容器实时内存使用
kubectl exec -it <pod-name> -n msp-prod -- cat /proc/meminfo | grep -E "MemTotal|MemFree|MemAvailable"

# Java 堆内存详情
kubectl exec -it <pod-name> -n msp-prod -- jcmd 1 GC.heap_info
```

**步骤 6：调整配置并重启**

```bash
# 更新 Helm values 后滚动重启
helm upgrade <release-name> <chart-path> -f values-fixed.yaml -n msp-prod

# 或直接 patch
kubectl set resources deployment/<name> -n msp-prod \
  --limits=memory=3Gi --requests=memory=2Gi

kubectl rollout restart deployment/<name> -n msp-prod
```

## 监控指标

```promql
# 1. 容器实际内存使用量（按 Pod 分组）
container_memory_working_set_bytes{namespace="msp-prod", container!=""}

# 2. 容器内存使用率（相对 limit，超过 80% 预警）
container_memory_working_set_bytes{namespace="msp-prod"} / container_spec_memory_limit_bytes{namespace="msp-prod"}

# 3. OOM 事件计数（1 小时内增量）
increase(container_oom_events{namespace="msp-prod"}[1h])

# 4. JVM 堆内存使用（需 JMX exporter）
jvm_memory_bytes_used{area="heap", namespace="msp-prod"}

# 5. 容器重启次数增长率
rate(kube_pod_container_status_restarts_total{namespace="msp-prod"}[5m]) > 0

# 6. 节点内存使用率（判断是否节点级 OOM）
1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)

# 7. cspm monitorservice 内存趋势（按 10 分钟采样，观察泄漏趋势）
avg_over_time(container_memory_working_set_bytes{namespace="msp-prod", pod=~"cspm-ms-monitorservice.*"}[10m])
```

在 VictoriaMetrics 中配置：容器内存使用率 > 80% 预警、> 90% 告警；OOM 事件 > 0 立即告警；JVM 堆使用率 > 85% 预警。

## 真实案例

**环境**：大连政务云项目，msp-prod 命名空间
**时间线**：
- 13:50 — cspm-ms-monitorservice Pod 启动，limits.memory=2Gi
- 14:15 — Pod 第一次 OOMKilled，RESTARTS=1
- 14:45 — Pod 第二次 OOMKilled，RESTARTS=2，此后每约 30 分钟 OOM 一次
- 15:00 — 值班人员收到 VictoriaMetrics 告警：容器内存使用率 > 90%
- 15:05 — `kubectl describe pod` 确认 Last State Reason=OOMKilled，Exit Code=137
- 15:08 — `kubectl logs --previous | grep OutOfMemory`，未发现 Java OOM 异常
- 15:10 — `kubectl exec -- jcmd 1 VM.flags` 发现 `-Xmx2048m`，等于容器 limit 2Gi
- 15:12 — `kubectl exec -- jcmd 1 GC.heap_info` 显示堆使用仅 1.5Gi，未到 Xmx 上限
- 15:15 — 确认根因：非堆内存（Metaspace + 直接内存 + 线程栈）在采集 200+ 指标时增长至 0.6Gi，总用量 2.1Gi 超过容器 limit 2Gi

**根因分析**：cspm-ms-monitorservice 的 Helm values 中 Java 启动参数硬编码 `-Xmx2048m`，刚好等于容器 limits.memory=2Gi。该服务采集 10 种产品类型共 200+ 监控指标，高并发采集时 Metaspace（加载指标定义类）、直接内存（NIO 缓冲区）、线程栈（200+ 采集线程）等非堆内存增长至 0.6Gi，总内存 2.1Gi 超过 limit 2Gi，被 cgroups OOM Killer 杀死。JVM 堆本身未到上限，故无 OutOfMemoryError 日志。

**处置步骤**：
1. 将 Helm values 中 `-Xmx2048m` 替换为 `-XX:MaxRAMPercentage=60`（容器 limit 3Gi 时堆约 1.8Gi）
2. 容器 limits.memory 上调至 3Gi，requests.memory 设为 2Gi
3. 执行 `helm upgrade cspm ./cspm-chart -f values-dalian-fixed.yaml -n msp-prod`
4. 验证：Pod 连续运行 72 小时未再 OOM，内存使用稳定在 1.8-2.2Gi
5. VictoriaMetrics 增加容器内存使用率 80% 预警和 90% 告警
6. 在 Helm values 模板中统一将 Java 服务的 `-Xmx` 替换为 `-XX:MaxRAMPercentage`

## 处置建议

- Java 服务严禁硬编码 -Xmx 等于容器 limit，必须使用 -XX:MaxRAMPercentage（建议 60-70%）让 JVM 自适应容器内存。
- Helm values.yaml 中 limits.memory 应为堆内存的 1.5 倍以上，为 Metaspace、线程栈、直接内存预留空间。
- VictoriaMetrics 监控中配置容器内存使用率达 80% 预警、90% 告警，提前发现内存泄漏趋势。
- 对于 monitorservice 等采集类服务，建议定期 dump 堆内存（jmap）分析是否存在指标数据缓存泄漏。

## 预防措施

1. **JVM 参数标准化**：所有 Java 服务的 Helm values 模板统一使用 `-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=256m`，禁止硬编码 -Xmx。cspm 建议堆占比 60%，adapter 建议堆占比 50%（预留直接内存给 HTTP 客户端）。
2. **内存 limit 规划**：limits.memory 按堆内存的 1.5 倍设置。monitorservice 采集 200+ 指标建议 limit 不低于 3Gi；普通 cspm 业务服务建议 2Gi；adapter 云 API 适配建议 2Gi。政务云节点需提前评估总内存是否满足。
3. **内存泄漏巡检**：通过 VictoriaMetrics 的 `avg_over_time` 函数按天统计容器内存趋势，若呈持续上升趋势（日均增长 > 5%），自动创建工单排查泄漏。
4. **压测验证**：新版本上线前在 POC2/POC4 环境执行压力测试，模拟 200+ 指标并发采集场景，验证内存使用不超过 limit 的 80%。
5. **优雅关闭**：应用配置 `SIGTERM` 信号处理，OOMKilled 前尽可能完成当前请求处理。kubelet 的 terminationGracePeriodSeconds 建议设为 60s。
6. **多副本分散**：monitorservice 建议部署 2+ 副本并配置反亲和性（podAntiAffinity），避免单副本 OOM 导致采集完全中断。

## 相关命令速查

```bash
# 确认 OOMKilled
kubectl describe pod <pod-name> -n msp-prod
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.status.containerStatuses[0].lastState}'

# 查看崩溃前日志
kubectl logs <pod-name> -n msp-prod --previous
kubectl logs <pod-name> -n msp-prod --previous | grep -i "OutOfMemory\|OOM\|heap"

# 查看内存配置
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.spec.containers[0].resources}'

# JVM 诊断
kubectl exec -it <pod-name> -n msp-prod -- jcmd 1 VM.flags
kubectl exec -it <pod-name> -n msp-prod -- jcmd 1 GC.heap_info
kubectl exec -it <pod-name> -n msp-prod -- jmap -heap 1
kubectl exec -it <pod-name> -n msp-prod -- env | grep JAVA_OPTS

# 实时内存
kubectl exec -it <pod-name> -n msp-prod -- cat /proc/meminfo
kubectl top pod <pod-name> -n msp-prod

# 调整资源并重启
kubectl set resources deployment/<name> -n msp-prod --limits=memory=3Gi
kubectl rollout restart deployment/<name> -n msp-prod
kubectl rollout status deployment/<name> -n msp-prod

# Helm 升级
helm upgrade <release-name> <chart-path> -f values.yaml -n msp-prod

# 查看 Pod 内存使用
kubectl top pods -n msp-prod --sort-by=memory

# 节点内存
kubectl top nodes --sort-by=memory
kubectl describe node <node-name> | grep -A 5 "MemoryPressure"
```
