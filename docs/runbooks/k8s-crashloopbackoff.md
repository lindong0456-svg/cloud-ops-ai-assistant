# K8s CrashLoopBackOff 排障

## 故障现象

在 msp-prod 命名空间中，Pod 反复启动后崩溃，`kubectl get pods` 显示 STATUS 为 CrashLoopBackOff。容器以指数退避方式不断重启（10s → 20s → 40s → ... 上限 5min），服务无法稳定运行。典型表现：

```
NAME                              READY   STATUS             RESTARTS   AGE
adapter-cloud-5f6g7h8i9-q4w7e    0/1     CrashLoopBackOff   7          6m32s
cspm-biz-7d4f5c6b8-x2k9m         1/1     Running            0          15m
```

dispatch 网关（9091 端口）因后端 adapter Pod 不可用，健康检查失败，上游收到 502 Bad Gateway 错误，用户云资源操作请求被拒绝。

## 影响评估

- **直接影响**：CrashLoopBackOff 状态的 Pod 无法承接流量，对应服务完全不可用。若为 adapter 服务崩溃，cspm 业务层无法调用云厂商 API，导致所有云资源操作（查询/创建/修改）中断。
- **连锁反应**：三层架构中任一层 CrashLoopBackOff 都会阻断调用链：dispatch → cspm → adapter。若 cspm 崩溃，dispatch 网关直接 502；若 adapter 崩溃，cspm 请求超时后级联失败。
- **波及范围**：影响对应政务云项目（湖南/西安/大连/烟台）的全部云资源管理功能，200+ 监控指标采集中断，告警可能误报或漏报。
- **业务等级**：P1 级故障，需 10 分钟内定位根因，20 分钟内恢复。

## 关联组件

| 组件 | 说明 |
|------|------|
| kubelet | 负责 Pod 生命周期管理，检测容器退出后按退避策略重启 |
| ConfigMap/Secret | 应用配置来源，配置错误是 CrashLoopBackOff 的常见根因 |
| Nacos 配置中心 | msp-prod 服务从 Nacos 拉取动态配置，连接失败导致启动崩溃 |
| livenessProbe/readinessProbe | 健康检查配置不当可能导致 Pod 被误杀 |
| dispatch 网关 (9091) | 后端 Pod 不可用时返回 502 |
| Helm | values.yaml 中的配置参数直接影响应用启动行为 |

## 常见原因

1. **应用启动失败**：代码异常、依赖的数据库或中间件不可达导致进程退出。Exit Code 通常为 1。
2. **配置错误**：ConfigMap 或 Secret 中关键参数缺失或值不正确，如 Nacos 地址、数据库连接串、namespace 配置不匹配。
3. **依赖服务不可达**：下游服务未就绪导致健康检查失败。如 cspm 依赖 adapter，adapter 未启动时 cspm 的 readinessProbe 失败。
4. **权限问题**：ServiceAccount 权限不足或文件挂载路径权限错误，导致应用读取配置文件失败。
5. **资源限制触发**：容器内存 limit 过低，启动阶段内存峰值超过 limit 被 OOMKilled，表现为 CrashLoopBackOff。
6. **时区/环境差异**：POC 环境与政务云生产环境的时区、字符集配置不一致导致应用启动异常。

## 排障步骤

**步骤 1：查看上次崩溃日志**

```bash
kubectl logs <pod-name> -n msp-prod --previous
```

期望输出（示例）：
```
2024-01-15 14:23:01 ERROR [main] c.u.m.adapter.AdapterApplication - Failed to start
org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'nacosConfigService':
  nested exception is com.alibaba.nacos.api.exception.NacosException: client is not connected to server: 172.25.1.100:8848
```

若日志为空，说明容器在启动阶段即崩溃，需检查启动命令和镜像入口。

**步骤 2：查看 Pod 详情与退出码**

```bash
kubectl describe pod <pod-name> -n msp-prod
```

重点查看 Containers 区域的 Last State：

```
Containers:
  adapter-cloud:
    State:          Waiting
      Reason:       CrashLoopBackOff
    Last State:     Terminated
      Reason:       Error
      Exit Code:    1
      Started:      Mon Jan 15 14:22:30 2024
      Finished:     Mon Jan 15 14:22:45 2024
    Ready:          False
    Restart Count:  7
```

- Exit Code 1：应用异常
- Exit Code 137：OOMKilled（参见 OOMKilled 排障文档）
- Exit Code 255：容器运行时异常

**步骤 3：检查配置来源**

```bash
# 查看 ConfigMap
kubectl get configmap -n msp-prod -o yaml | grep -A 3 nacos

# 查看 Secret
kubectl get secret -n msp-prod -o yaml | grep -A 3 database

# 对比 Helm values 中的配置引用
helm get values <release-name> -n msp-prod
```

**步骤 4：进入容器测试依赖连通性**

```bash
# 若容器短暂存活可 exec 进入
kubectl exec -it <pod-name> -n msp-prod -- sh

# 在容器内测试 Nacos 连通性
curl -s http://172.25.1.100:8848/nacos/v1/ns/instance/list?serviceName=cspm-svc

# 测试下游服务
curl -s http://adapter-svc:8080/actuator/health
```

**步骤 5：检查健康检查配置**

```bash
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.spec.containers[0].livenessProbe}'
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.spec.containers[0].readinessProbe}'
```

确认 livenessProbe 的 initialDelaySeconds 是否过短，导致应用未完成启动即被判定为不健康。

## 监控指标

```promql
# 1. 容器重启次数增长率（5 分钟内重启 > 0 即告警）
rate(kube_pod_container_status_restarts_total{namespace="msp-prod"}[5m]) > 0

# 2. CrashLoopBackOff 状态 Pod 数量
count by (pod) (kube_pod_container_status_waiting_reason{reason="CrashLoopBackOff", namespace="msp-prod"} == 1)

# 3. 容器退出码分布（按 Exit Code 分组）
count by (reason) (kube_pod_container_status_last_terminated_reason{namespace="msp-prod"})

# 4. Pod 就绪率（可用副本 / 期望副本）
sum by (deployment) (kube_deployment_status_replicas_available{namespace="msp-prod"}) / sum by (deployment) (kube_deployment_spec_replicas{namespace="msp-prod"})

# 5. dispatch 网关 502 错误率（后端 Pod 不可用时激增）
rate(http_requests_total{code="502", service="dispatch"}[5m])
```

在 VictoriaMetrics 中配置：容器重启率 > 0 持续 2 分钟即触发 P1 告警，Pod 就绪率 < 0.5 触发 P2 告警。

## 真实案例

**环境**：烟台政务云项目，msp-prod 命名空间
**时间线**：
- 09:00 — Ansible 推送新版本 adapter 镜像，Helm 执行 `helm upgrade adapter ./adapter-chart -f values-yantai.yaml -n msp-prod`
- 09:02 — adapter Pod 开始 CrashLoopBackOff，每 2 分钟重启一次，RESTARTS 计数快速增长
- 09:05 — 执行 `kubectl logs adapter-cloud-5f6g7h8i9-q4w7e -n msp-prod --previous`，发现报错 `Nacos connection refused: 172.25.1.100:8848`
- 09:08 — 在容器内执行 `curl http://172.25.1.100:8848/nacos/v1/ns/instance/list`，确认 Nacos 服务可达
- 09:10 — 排查 Helm values，发现 `nacos.namespace` 误配为 `msp-dev`，而生产环境应为 `msp-prod`
- 09:12 — 确认根因：配置中心在 `msp-dev` namespace 下找不到 adapter 的数据源连接串配置，应用初始化阶段抛异常退出

**根因分析**：烟台政务云部署时，从 POC2 环境复制 values.yaml 未修改 namespace 字段。Ansible 镜像管理流程中未包含 values.yaml 的字段一致性校验，导致配置漂移。

**处置步骤**：
1. 修正 values-yantai.yaml 中 `nacos.namespace` 为 `msp-prod`
2. 执行 `helm upgrade adapter ./adapter-chart -f values-yantai.yaml -n msp-prod`
3. 验证：`kubectl get pods -n msp-prod | grep adapter`，STATUS 为 Running，RESTARTS 为 0
4. 验证调用链：`curl http://dispatch-svc:9091/health`，返回 200
5. 在 GitLab CI 中增加 values.yaml 中 namespace 字段的一致性校验脚本，防止再次发生

## 处置建议

- Helm 部署前用 `helm template` 渲染并人工校验关键配置项（Nacos 地址、数据库连接串、namespace）。
- 为关键服务配置合理的 readinessProbe 和 livenessProbe，initialDelaySeconds 应覆盖应用启动时间（cspm 建议 60s，adapter 建议 30s）。
- 建议在 CI/CD 流水线中增加配置预检脚本，对比 ConfigMap 与 Secret 的 key 是否与代码引用一致。
- 政务云环境（如湖南、烟台）通过 VPN 访问 Nacos 时，注意网络延迟可能导致心跳超时，建议调大超时参数至 10s。

## 预防措施

1. **配置预检脚本**：在 Helm 部署前执行自动化校验，对比 values.yaml 中 namespace、Nacos 地址、数据库连接串等关键字段与环境配置模板是否一致。
2. **启动探针优化**：配置 startupProbe 代替 livenessProbe 的初始等待，避免应用慢启动被误杀。建议 cspm startupProbe failureThreshold=30、periodSeconds=5。
3. **配置版本管理**：各环境（POC2/POC4/POC7/POC15 及政务云）的 values.yaml 独立维护，通过 Git 分支管理，禁止跨环境复制。
4. **依赖就绪等待**：在应用启动脚本中增加依赖服务就绪检查（wait-for 模式），确保 Nacos、数据库可达后再启动主进程。
5. **告警联动**：VictoriaMetrics 中配置容器重启率告警，CrashLoopBackOff 超过 2 次重启即通知值班人员。

## 相关命令速查

```bash
# 查看崩溃 Pod
kubectl get pods -n msp-prod | grep CrashLoopBackOff
kubectl logs <pod-name> -n msp-prod --previous
kubectl logs <pod-name> -n msp-prod --previous --tail=100

# 查看 Pod 详情与退出码
kubectl describe pod <pod-name> -n msp-prod
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.status.containerStatuses[0].lastState}'

# 检查配置
kubectl get configmap -n msp-prod -o yaml
kubectl get secret -n msp-prod -o yaml

# 进入容器排障
kubectl exec -it <pod-name> -n msp-prod -- sh
kubectl exec -it <pod-name> -n msp-prod -- curl http://adapter-svc:8080/actuator/health

# 检查健康检查配置
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.spec.containers[0].livenessProbe}'
kubectl get pod <pod-name> -n msp-prod -o jsonpath='{.spec.containers[0].readinessProbe}'

# Helm 操作
helm get values <release-name> -n msp-prod
helm template <chart-path> -f values.yaml
helm upgrade <release-name> <chart-path> -f values.yaml -n msp-prod

# 滚动重启
kubectl rollout restart deployment/<name> -n msp-prod
kubectl rollout status deployment/<name> -n msp-prod

# 查看事件
kubectl get events -n msp-prod --sort-by='.lastTimestamp' | tail -20
```
