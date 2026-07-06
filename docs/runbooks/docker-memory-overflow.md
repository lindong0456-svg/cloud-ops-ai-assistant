# 容器内存溢出排障

## 故障现象

容器运行一段时间后被 OOM Killer 终止，Kubernetes Pod 状态显示 OOMKilled 并触发重启（CrashLoopBackOff）。`docker stats` 观察到容器内存使用量持续攀升直至触顶。Java 服务表现为频繁 Full GC、响应延迟激增，最终进程被系统 kill。`kubectl describe pod` 的 Last State 中 ExitCode 为 137。在 MSP 平台中，cspm-ms-monitorservice 等监控采集服务因处理大量数据点，是 OOM 高发服务。

## 影响评估

内存溢出属于 P1 级故障，导致服务间歇性不可用。容器 OOM 后重启期间（通常 30-120 秒），业务接口不可达，上游调用方报连接超时。MSP 平台 cspm-ms-monitorservice 负责监控指标采集与转发，OOM 后导致监控数据丢失、告警规则触发延迟、大盘数据出现断点。CrashLoopBackOff 期间 Pod 反复重启，消耗节点资源并影响调度。若多个服务同时 OOM，可能引发节点内存压力，触发 Kubelet 驱逐其他 Pod，扩大影响范围。

## 关联组件

- **JVM 运行时**：Java 11+（容器内运行，需支持 cgroup 感知）
- **K8s 资源管理**：resources.limits.memory、resources.requests.memory
- **监控系统**：VictoriaMetrics（cspm-ms-monitorservice 调用其 API 查询监控指标）
- **业务链路**：dispatch（9091）→ cspm → adapter（cspm 负责指标采集与处理）
- **GC 诊断工具**：jcmd、jstat、jmap、Arthas
- **Heap 分析工具**：MAT（Eclipse Memory Analyzer）、JProfiler
- **部署工具**：Helm（values.yaml 中配置 resources）

## 常见原因

1. **容器 memory limit 过低**：Kubernetes resources.limits.memory 设置不合理，未给应用预留足够堆空间。Java 服务设置 2Gi limit 但 JVM 堆 + Metaspace + 堆外内存总和超出限制。
2. **应用内存泄漏**：代码中存在未释放的资源引用，如未关闭的数据库连接、无限增长的缓存或 Map。每次请求泄漏少量内存，长时间运行后累积触发 OOM。
3. **JVM 堆未限制**：Java 容器未设置 `-XX:MaxRAMPercentage`，JVM 根据宿主机内存（而非容器 limit）计算堆大小，超出容器 limit 后被 OOM Killer 终止。
4. **缓存无上限**：业务缓存（如 Caffeine、Guava Cache）未配置最大容量和过期策略，数据持续写入导致内存打满。
5. **大结果集加载**：查询数据库或 API 时未做分页，单次将大量数据加载到内存。MSP 平台 cspm 服务调用 VictoriaMetrics API 单次返回 50 万条数据点导致 OOM。
6. **线程泄漏**：应用创建大量线程未释放，每个线程默认占用约 1MB 栈空间，数百线程即可消耗大量内存。

## 排障步骤

1. **实时监控容器内存**：
   ```bash
   docker stats <container_id>
   # 或 Kubernetes
   kubectl top pod <pod_name> -n <namespace>
   ```
   预期输出：观察内存使用量和限制值，若使用量持续攀升至接近 limit，确认内存溢出趋势。

2. **确认 OOMKilled 状态**：
   ```bash
   kubectl describe pod <pod_name> -n <namespace> | grep -A5 "Last State"
   ```
   预期输出：`Reason: OOMKilled`，`Exit Code: 137`，`Started` 和 `Finished` 时间戳显示上次被 kill 的时间。

3. **检查 JVM 启动参数**：
   ```bash
   docker exec <container> jcmd <pid> VM.flags | grep -iE "heap|MaxRAM"
   # 或查看进程启动参数
   docker exec <container> jinfo -flag MaxRAMPercentage <pid>
   ```
   预期输出：确认是否设置了 `MaxRAMPercentage`，若未设置则 JVM 使用宿主机内存计算堆大小。

4. **分析 GC 日志**：
   ```bash
   docker exec <container> jstat -gcutil <pid> 1000 10
   ```
   预期输出：每秒输出一次 GC 统计，观察 Old 区使用率（O 列）是否持续增长至 90%+，Full GC 次数（FGC 列）是否快速递增。

5. **分析 Heap Dump**（若启用了 `-XX:+HeapDumpOnOutOfMemoryError`）：
   ```bash
   # 查找 heap dump 文件
   docker exec <container> find / -name "*.hprof" 2>/dev/null
   # 导出 heap dump
   kubectl cp <pod>:/tmp/heapdump.hprof ./heapdump.hprof -n <namespace>
   # 使用 MAT 分析大对象和引用链
   ```
   预期输出：MAT 诊断报告中显示占用内存最大的对象及其 GC Root 引用链。

6. **检查应用日志**：
   ```bash
   kubectl logs <pod> -n <namespace> --previous | grep -i "OutOfMemoryError"
   ```
   预期输出：确认是 "Java heap space"（堆溢出）还是 "Metaspace"（元空间溢出）。

7. **检查 Helm values 资源配置**：
   ```bash
   helm get values <release> -n <namespace> | grep -A5 resources
   ```

## 真实案例

**故障时间线**：

- **T+0**：联通数科 MSP 平台 msp-prod 命名空间，cspm-ms-monitorservice Java 服务运行 2 小时后首次 OOMKilled，Pod 自动重启。
- **T+10min**：运维收到告警，`kubectl get po -n msp-prod | grep monitorservice` 显示 RESTARTS 为 3，状态 CrashLoopBackOff。
- **T+15min**：执行 `kubectl describe pod <pod> -n msp-prod | grep -A5 "Last State"`，确认 ExitCode 137，Reason OOMKilled。
- **T+20min**：执行 `kubectl top pod <pod> -n msp-prod`，内存使用 1900Mi/2048Mi（limit 为 2Gi），接近触顶。
- **T+25min**：进入容器执行 `jcmd <pid> VM.flags`，发现未设置 `MaxRAMPercentage`，JVM 根据节点 32GB 内存计算堆大小为约 8GB，远超容器 2Gi limit。
- **T+30min**：分析 GC 日志 `jstat -gcutil <pid> 1000 10`，Old 区使用率从 30% 持续攀升至 95%，Full GC 频率从每分钟 1 次增至每分钟 15 次。
- **T+40min**：查看应用日志，发现 OutOfMemoryError: Java heap space，堆栈指向 VictoriaMetrics API 调用方法。
- **T+50min**：确认根因——该服务调用 VictoriaMetrics API 查询监控指标时，单次查询返回结果集超过 50 万条数据点，未做分页处理，全部加载到内存中导致 Old 区被打满。

**根因分析**：cspm-ms-monitorservice 在 dispatch（9091）→ cspm → adapter 架构中负责监控指标采集。查询 VictoriaMetrics API 时未限制返回结果集大小，单次查询返回 50 万条数据点，序列化后占用约 1.5GB 堆内存。同时 JVM 未设置 `MaxRAMPercentage`，默认使用宿主机 32GB 内存计算堆大小（约 8GB），与容器 2Gi 的 memory limit 严重不匹配。JVM 认为堆空间充足不触发 GC，但容器内存已触顶被 OOM Killer 终止。每次重启后重新查询同样的大结果集，形成 OOM 循环。故障导致监控数据采集间歇性中断约 3 小时。

**处置步骤**：
1. 调整 JVM 参数：设置 `-XX:MaxRAMPercentage=75 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/`。
2. 对 VictoriaMetrics 查询结果做分页处理，单次查询限制最多返回 5000 条记录。
3. 在查询层加入熔断器，当单次查询响应体超过 10MB 时熔断并返回降级数据。
4. 通过 Helm 更新部署：`helm upgrade <release> -n msp-prod -f values.yaml`，调整 resources.limits.memory 为 4Gi。
5. 重启 Pod 验证：`kubectl delete po <pod> -n msp-prod`，观察 24 小时内存使用稳定在 60% 以下。

## 处置建议

三步修复：第一步调整 JVM 参数，设置 `-XX:MaxRAMPercentage=75` 确保 JVM 堆占容器内存的 75%，预留 25% 给 Metaspace、线程栈和堆外内存。第二步对 VictoriaMetrics 查询结果做分页处理，单次查询限制最多返回 5000 条记录，超出部分分批拉取。第三步在查询层加入 Circuit Breaker（熔断器），当单次查询响应体超过 10MB 时熔断并返回降级数据，防止内存瞬时打满。调整后 cspm-ms-monitorservice 稳定运行，内存使用率维持在 60% 以下，未再出现 OOM。

## 预防措施

1. **JVM 容器感知**：所有 Java 容器必须设置 `-XX:MaxRAMPercentage=75`，在 Dockerfile 或 Helm values 中固化：
   ```yaml
   env:
     - name: JAVA_OPTS
       value: "-XX:MaxRAMPercentage=75 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/"
   ```
2. **资源 limit 合理规划**：根据应用实际内存消耗设置 limit，Java 服务建议最低 2Gi，数据处理类服务建议 4Gi。
3. **查询分页强制**：所有 API 调用和数据库查询强制分页，单次返回数据量不超过 10MB 或 5000 条。
4. **缓存上限配置**：Caffeine/Guava Cache 必须配置 `maximumSize` 和 `expireAfterWrite`，禁止无界缓存。
5. **内存监控告警**：配置容器内存使用率 80% 告警，提前发现内存增长趋势。
6. **压力测试**：新服务上线前进行长时间内存压力测试，验证 24 小时内无内存泄漏。

## 相关命令速查

```bash
# 容器内存监控
docker stats <container_id>
kubectl top pod <pod> -n <ns>

# 确认 OOMKilled
kubectl describe pod <pod> -n <ns> | grep -A5 "Last State"

# JVM 参数检查
docker exec <container> jcmd <pid> VM.flags
docker exec <container> jinfo -flag MaxRAMPercentage <pid>

# GC 日志分析
docker exec <container> jstat -gcutil <pid> 1000 10

# Heap Dump 导出与分析
kubectl cp <pod>:/tmp/heapdump.hprof ./heapdump.hprof -n <ns>

# 应用日志排查
kubectl logs <pod> -n <ns> --previous | grep -i "OutOfMemoryError"

# Helm 资源配置
helm get values <release> -n <ns> | grep -A5 resources

# 重启 Pod
kubectl delete po <pod> -n <ns>
kubectl get po -n msp-prod | grep <service> | awk '{print "kubectl -n msp-prod delete po "$1}' | sh
```
