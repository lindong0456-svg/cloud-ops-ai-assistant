# K8s Pod Pending 状态排障

## 故障现象

在 msp-prod 命名空间中，新部署或重启的 Pod 长时间停留在 Pending 状态，无法被调度到任何节点。执行 `kubectl get pods -n msp-prod` 时，STATUS 列显示为 Pending，容器未启动，服务无法对外提供流量。典型表现如下：

```
NAME                              READY   STATUS    RESTARTS   AGE
cspm-biz-7d4f5c6b8-x2k9m         0/1     Pending   0          12m
cspm-biz-7d4f5c6b8-p3l1n         0/1     Pending   0          12m
adapter-cloud-5f6g7h8i9-q4w7e    0/1     Pending   0          8m
```

此时 dispatch 网关（9091 端口）因后端 Pod 未就绪，健康检查返回 503，上游请求被拒绝或进入重试队列。

## 影响评估

- **直接影响**：Pending 状态的 Pod 无法承接流量，对应服务的可用副本数低于期望值，可能导致请求超时或失败。
- **连锁反应**：msp-prod 采用三层架构（dispatch 网关 → cspm 业务层 → adapter 云厂商 API 适配层），若 cspm 或 adapter 的 Pod Pending，dispatch 网关的健康检查会失败，导致整个调用链路中断，用户无法执行云资源操作。
- **波及范围**：涉及湖南、西安、大连、烟台等政务云项目，200+ 监控指标的采集与告警可能中断。
- **业务等级**：属于 P1 级故障，需在 15 分钟内定位原因，30 分钟内恢复服务。

## 关联组件

| 组件 | 说明 |
|------|------|
| kube-scheduler | 负责将 Pod 调度到合适节点，Pending 的直接原因是调度失败 |
| kubelet | 节点上的代理，上报节点资源容量与已分配量 |
| StorageClass/PV/PVC | 若 Pod 依赖持久化存储，PVC 绑定失败也会导致 Pending |
| Helm | 部署工具，values.yaml 中 resources.requests 直接影响调度决策 |
| dispatch 网关 (9091) | 受后端 Pod 未就绪影响，返回 503 |
| VictoriaMetrics | 监控平台，依赖 Pod 中的 exporter 上报指标 |

## 常见原因

1. **节点资源不足**：CPU/内存剩余量无法满足 Pod 的 resources.requests 声明值。集群中 adapter 服务因采集 200+ 指标，内存占用较高，容易挤占 cspm 的调度空间。
2. **调度约束冲突**：nodeSelector、nodeAffinity 或 taint/toleration 配置导致无可用节点。政务云 ARM 节点常通过 taint 隔离，若未配置对应 toleration 则调度失败。
3. **PVC 绑定失败**：依赖的 StorageClass 无可用 PV，或动态供给超时。cspm 服务的历史数据存储依赖 PVC。
4. **镜像拉取超时**：私有镜像仓库（如 172.25.1.250:5000）网络不通或认证失败，导致 ImagePullBackOff 后退避重试，此时状态可能先 Pending 再转为 ImagePullBackOff。
5. **ResourceQuota 超限**：命名空间的 ResourceQuota 已用尽，新 Pod 无法创建。

## 排障步骤

**步骤 1：查看 Pod 事件**

```bash
kubectl describe pod <pod-name> -n msp-prod
```

重点查看底部 Events 区域，关注 FailedScheduling 事件的 Reason 和 Message 字段：

```
Events:
  Type     Reason            Age   From               Message
  ----     ------            ----  ----               -------
  Warning  FailedScheduling  12m   default-scheduler  0/6 nodes are available: 3 Insufficient memory, 2 node(s) had untolerated taint {arch: arm64}, 1 node(s) had volume node affinity conflict.
```

上例表明：3 个节点内存不足，2 个节点有 ARM taint 未配置 toleration，1 个节点存储亲和性冲突。

**步骤 2：检查节点资源**

```bash
kubectl get nodes -o wide
kubectl describe node <node-name> | grep -A 15 "Allocated resources"
```

期望输出：
```
Allocated resources:
  (Total limits may be over 100 percent, i.e., overcommitted.)
  Resource           Requests      Limits
  --------           --------      ------
  cpu                14500m (90%)  16000m (100%)
  memory             28Gi (87%)   32Gi (100%)
```

若内存分配率超过 85%，说明节点资源紧张，需扩容或迁移 Pod。

**步骤 3：检查 PVC 状态**

```bash
kubectl get pvc -n msp-prod
kubectl get pv | grep <pvc-name>
```

期望输出（正常状态）：
```
NAME                STATUS   VOLUME                CAPACITY   STORAGECLASS
cspm-data-pvc       Bound    pvc-abc123            50Gi       nfs-client
```

若 STATUS 为 Pending，说明 PV 供给失败，需检查 StorageClass 和 NFS provisioner。

**步骤 4：检查镜像拉取**

```bash
kubectl get events -n msp-prod --field-selector reason=Failed
```

若出现 `Failed to pull image` 错误，检查节点到镜像仓库连通性：
```bash
# 在目标节点上执行
curl -k https://172.25.1.250:5000/v2/_catalog
kubectl get secret -n msp-prod | grep regcred
```

**步骤 5：校验 Helm 配置**

```bash
helm get values <release-name> -n msp-prod | grep -A 5 resources
helm template <chart-path> -f values.yaml | grep -A 5 resources
```

确认 resources.requests 是否设置过高，与节点实际容量不匹配。

## 监控指标

```promql
# 1. 当前 Pending 状态的 Pod 数量（按命名空间分组）
sum by (namespace) (kube_pod_status_phase{phase="Pending", namespace="msp-prod"} == 1)

# 2. 节点可用内存（字节）
node_memory_MemAvailable_bytes

# 3. 节点 CPU 分配率（已请求 / 可分配）
sum by (node) (kube_pod_container_resource_requests{resource="cpu", namespace="msp-prod"}) / kube_node_status_allocatable{resource="cpu"}

# 4. 节点内存分配率（已请求 / 可分配），超过 85% 需告警
sum by (node) (kube_pod_container_resource_requests{resource="memory", namespace="msp-prod"}) / kube_node_status_allocatable{resource="memory"}

# 5. PVC Pending 数量
count(kube_persistentvolumeclaim_status_phase{phase="Pending", namespace="msp-prod"} == 1)
```

在 VictoriaMetrics 中配置告警规则：节点内存分配率 > 85% 或 Pending Pod 数 > 0 时触发预警。

## 真实案例

**环境**：POC4 测试环境，msp-prod 命名空间
**时间线**：
- 14:00 — 通过 Helm 部署 cspm 服务 3 副本，执行 `helm upgrade cspm ./cspm-chart -f values-poc4.yaml -n msp-prod`
- 14:05 — 发现 3 个副本全部 Pending，`kubectl get pods` 确认
- 14:07 — 执行 `kubectl describe pod cspm-biz-7d4f5c6b8-x2k9m -n msp-prod`，Events 报 `Insufficient memory`
- 14:10 — 执行 `kubectl describe node worker-03`，Allocated resources 显示内存分配率 94%
- 14:12 — 进一步排查发现该节点上 adapter 服务占用 12Gi 内存（采集 200+ 指标），导致剩余内存仅 3.2Gi
- 14:15 — 查看 Helm values 确认 cspm 的 resources.requests.memory 设为 4Gi

**根因分析**：Helm 部署时 cspm 的 resources.requests.memory 设为 4Gi，而集群节点剩余内存仅 3.2Gi。adapter 服务因 POC4 环境接入了较多云厂商账号，指标采集量增大，内存占用超出预期。

**处置步骤**：
1. 临时处理：对 adapter 中非关键 Pod（POC4 测试环境）执行 `kubectl drain worker-03 --ignore-daemonsets --delete-emptydir-data`，腾出资源
2. 调整 cspm 的 resources.requests.memory 至 2Gi，执行 `helm upgrade cspm ./cspm-chart -f values-poc4-fixed.yaml -n msp-prod`
3. 验证：`kubectl get pods -n msp-prod | grep cspm`，3 个副本均 Running
4. 长期方案：为 cspm 配置 HPA 弹性伸缩，基于 CPU 和内存利用率自动扩缩容

## 处置建议

- Helm 部署前务必校验 resources.requests 与集群实际容量匹配，建议在 GitLab CI 流水线中加入 Kyverno 准入校验策略。
- 监控告警设置节点内存可用率低于 20% 时触发预警，避免被动等待 Pod Pending。
- 政务云项目（如西安、大连）因节点规格有限，建议为 cspm 与 adapter 设置合理的 requests/limits 比例（3:4），避免调度失败。
- 多镜像仓库环境（172.25.1.250:5000、172.25.4.250:5000）需确保各节点网络策略放通，通过 Ansible 定期巡检镜像拉取连通性。

## 预防措施

1. **资源容量预检**：Helm 部署前执行 `kubectl describe node` 获取各节点剩余资源，与 values.yaml 中 requests 对比，编写预检脚本集成到 CI/CD 流水线。
2. **ResourceQuota 配置**：为 msp-prod 命名空间设置 ResourceQuota，防止单个服务超额申请资源影响其他服务调度。
3. **HPA 弹性伸缩**：为 cspm 和 adapter 配置 HorizontalPodAutoscaler，基于 CPU/内存利用率自动扩缩容，避免固定副本数在资源紧张时无法调度。
4. **节点容量规划**：根据 10 种产品类型的指标采集量评估 adapter 内存需求，预留 30% 冗余。POC2/POC4/POC7/POC15 环境应定期评估节点规格是否满足需求。
5. **告警前置**：在 VictoriaMetrics 中配置节点内存分配率 > 80% 的预警告警，提前介入而非等 Pod Pending 后才发现。

## 相关命令速查

```bash
# 查看 Pending Pod
kubectl get pods -n msp-prod | grep Pending
kubectl describe pod <pod-name> -n msp-prod

# 查看节点资源
kubectl get nodes -o wide
kubectl describe node <node-name> | grep -A 15 "Allocated resources"
kubectl top nodes

# 查看 PVC 状态
kubectl get pvc -n msp-prod
kubectl get pv

# 查看调度事件
kubectl get events -n msp-prod --field-selector reason=FailedScheduling --sort-by='.lastTimestamp'

# Helm 相关
helm get values <release-name> -n msp-prod
helm template <chart-path> -f values.yaml | grep -A 5 resources
helm upgrade <release-name> <chart-path> -f values.yaml -n msp-prod

# 驱逐节点（临时腾出资源）
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data
kubectl uncordon <node-name>

# 查看 ResourceQuota
kubectl get resourcequota -n msp-prod
kubectl describe resourcequota -n msp-prod
```
