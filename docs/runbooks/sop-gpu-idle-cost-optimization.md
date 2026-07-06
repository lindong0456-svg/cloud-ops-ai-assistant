# GPU 闲置资源检测与成本优化 SOP

## 适用场景

适用场景：GPU 节点利用率持续低于 10% 但仍按全量计费，造成算力资源浪费和成本虚高。该 SOP 覆盖联通云 AICP 算力平台所有 GPU/NPU 实例，包括 aicp-notebook（交互式开发）、aicp-job（训练任务）、aicp-isvc（推理服务）三种产品类型，涉及 GPU 卡时计算和 NPU 利用率评估。

成本影响：单张 A100 GPU 每小时计费约 15-25 元（按量），月均成本约 10,800-18,000 元。若 10 张 GPU 持续闲置，月浪费成本约 10-18 万元。GPU 卡时计算公式：`GPU卡时 = 占用卡数 / 60`（分钟级统计），NPU 利用率计算公式：`NPU利用率 = 占用卡数 × 使用率 / 60`。闲置检测基于 VictoriaMetrics 采集的 200+ 指标，通过 dispatch（端口 9091）→ cspm → adapter 架构汇聚。

## 闲置判定标准

| 闲置等级 | GPU 利用率 | 持续时间 | 处置策略 | 成本影响 |
|---------|-----------|---------|---------|---------|
| 轻度闲置 | 5%-10% | 持续 24h | 标记观察，通知租户 | 计费正常但利用率低 |
| 中度闲置 | <5% | 持续 12h | 告警通知，建议降配或释放 | 浪费 80%+ 算力成本 |
| 重度闲置 | <1% | 持续 6h | 自动通知租户确认释放 | 完全浪费算力成本 |
| 僵尸实例 | 0% | 持续 72h | 自动回收并通知 | 100% 成本浪费 |

GPU 利用率通过 `nvidia-smi` 采集的 GPU-Util 指标衡量，数据由 Node Exporter 配合 DCGM Exporter 采集写入 VictoriaMetrics。NPU 利用率通过昇腾 `npu-smi` 采集。

## 检测工具与指标

1. **nvidia-smi 实时监控**：登录 GPU 节点执行 `nvidia-smi --query-gpu=index,gpu_util,memory.used,memory.total --format=csv -l 5`，每 5 秒采样一次 GPU 利用率和显存使用率。连续 6 次采样 GPU-Util 均低于 5% 可判定为闲置。
2. **DCGM Exporter 指标**：通过 VictoriaMetrics 查询 `DCGM_FI_DEV_GPU_UTIL{node="$gpu_node"}` 获取 GPU 计算利用率，`DCGM_FI_DEV_FB_USED / DCGM_FI_DEV_FB_FREE` 获取显存使用率。PromQL 查询近 24 小时均值：`avg_over_time(DCGM_FI_DEV_GPU_UTIL{node="$gpu_node"}[24h])`。
3. **计费流水核对**：通过联通云计费 API 查询 GPU 实例近 30 天计费流水，计算单卡日均成本和月度累计成本。对比 GPU 利用率时序数据，识别「高计费低利用」实例。
4. **任务队列检查**：通过 Kubernetes 查询 GPU 节点上的 Pod 列表 `kubectl get pods -A -o wide | grep gpu-node`，确认是否有训练任务或推理服务在运行。若 Pod 状态为 Running 但 GPU 利用率极低，通常为「占卡不干活」。
5. **调度日志分析**：检查 Kubernetes Scheduler 日志 `kubectl logs -n kube-system kube-scheduler-*`，确认 GPU 资源分配记录，排查是否存在资源预留但未实际使用的情况。

## 关键 PromQL

```promql
# 1. GPU 利用率（24h 均值）——核心闲置判定指标
# DCGM_FI_DEV_GPU_UTIL：DCGM Exporter 采集的 GPU 计算利用率
avg_over_time(DCGM_FI_DEV_GPU_UTIL{node="$gpu_node"}[24h])

# 2. GPU 显存使用率——辅助判断是否真正在使用
DCGM_FI_DEV_FB_USED{node="$gpu_node"} / (DCGM_FI_DEV_FB_USED{node="$gpu_node"} + DCGM_FI_DEV_FB_FREE{node="$gpu_node"})

# 3. NPU 利用率（昇腾场景）
avg_over_time(npudevice_duty{instance="$npu_node"}[24h])

# 4. GPU 卡时统计——计费依据
# GPU卡时 = 占用卡数 / 60（分钟级统计）
sum(GPU_ALLOCATED{node="$gpu_node"}) / 60

# 5. GPU 闲置时长——判定僵尸实例
count_over_time(DCGM_FI_DEV_GPU_UTIL{node="$gpu_node"} < 1[72h]) / count_over_time(DCGM_FI_DEV_GPU_UTIL{node="$gpu_node"}[72h])

# 6. 单卡日均成本——成本量化
# 需与计费系统联动，此处为利用率与成本关联查询
avg_over_time(DCGM_FI_DEV_GPU_UTIL{node="$gpu_node"}[24h]) * on(node) group_left COST_PER_GPU_HOUR

# 7. 集群 GPU 闲置率总览
count(avg_over_time(DCGM_FI_DEV_GPU_UTIL[24h]) < 5) / count(DCGM_FI_DEV_GPU_UTIL)
```

## 优化方案

### 方案一：释放闲置 GPU 实例

适用场景：确认无业务使用的僵尸实例（GPU 利用率 0% 持续 72h+）。

操作步骤：（1）通过 VM 查询 `avg_over_time(DCGM_FI_DEV_GPU_UTIL{node="$node"}[72h])` 确认 72 小时利用率均值为 0；（2）检查该节点上是否有 Running 状态的 Pod，确认无活跃任务；（3）通知租户确认释放，等待 24 小时无反馈则执行回收；（4）通过 Kubernetes 执行 `kubectl cordon $node && kubectl drain $node --ignore-daemonsets` 驱逐工作负载；（5）通过联通云 API 释放 GPU 实例，停止计费。

### 方案二：降配至低成本实例

适用场景：GPU 利用率 5%-10%，有轻量推理需求但不需要高端卡。

操作步骤：（1）评估实际 GPU 利用率峰值和均值，确认降配可行性；（2）选择低规格 GPU 实例（如从 A100 降配至 T4），计算降配后成本节省；（3）通过联通云 API 执行实例变配，迁移工作负载至新实例；（4）验证推理服务在新实例上正常运行后释放原实例。

### 方案三：跨租户调度与共享

适用场景：多租户环境下部分租户 GPU 闲置而另一些租户资源不足。

操作步骤：（1）通过 VM 查询所有租户的 GPU 利用率分布，识别闲置租户和过载租户；（2）通过 Kubernetes 配额管理调整租户 ResourceQuota，回收闲置租户的 GPU 配额；（3）将回收的 GPU 配额分配给过载租户，提升整体利用率；（4）配置 GPU 共享调度策略（MIG/MPS），允许多个轻量任务共享单张 GPU。

### 方案四：配置自动缩容策略

适用场景：训练任务集群，任务完成后 GPU 应自动释放。

操作步骤：（1）配置 Kubernetes Cluster Autoscaler，当 GPU 节点利用率低于阈值（10%）持续超过设定时间（30min）后自动缩容；（2）训练任务 Pod 配置 `resources.limits.nvidia.com/gpu: 1`，任务完成后 Pod 终止触发节点缩容；（3）通过联通云 API 设置 GPU 实例自动释放时间，避免遗忘释放。

## 真实案例

**时间线**：2024 年 4 月，联通数科 MSP AICP 算力平台 POC4 集群。

**T+0min**：XXL-JOB 定时成本巡检任务发现 POC4 集群 8 张 A100 GPU 中 5 张利用率持续低于 5%，触发成本优化告警。

**T+10min**：通过 VM `/v2/oneAgg` 查询 5 张 GPU 近 7 天利用率时序，确认 `gpu-node-04`、`gpu-node-05`、`gpu-node-06` 三张卡 7 天平均利用率分别为 2.1%、0.8%、3.5%，`gpu-node-07`、`gpu-node-08` 两张卡 7 天平均利用率为 0%（僵尸实例）。

**T+20min**：执行 `kubectl get pods -A -o wide | grep gpu-node` 检查 Pod 分布，`gpu-node-07/08` 无任何 Pod 运行，确认为僵尸实例。`gpu-node-04/05/06` 各有 1 个 notebook Pod 运行，但 GPU 利用率极低，属于「占卡不干活」。

**T+30min**：通过计费 API 查询 5 张 A100 近 30 天计费流水，单卡日均成本约 480 元，5 张卡月浪费成本约 72,000 元。

**T+40min**：通知相关租户确认释放，`gpu-node-07/08` 等待 24 小时无反馈后自动回收。`gpu-node-04/05/06` 租户确认 notebook 已不再使用，同意释放。

**根因**：（1）AI 开发人员申请 GPU 资源后忘记释放，notebook 会话断开但 Pod 未终止；（2）训练任务完成后未配置自动释放策略；（3）缺乏 GPU 利用率与计费的关联告警机制。

**处置**：（1）释放 5 张闲置 A100，月节省成本约 72,000 元；（2）配置 notebook Pod 空闲超时自动终止策略（30 分钟无活动自动停止）；（3）配置 GPU 闲置告警：利用率 <5% 持续 12h 触发钉钉通知；（4）配置 Cluster Autoscaler 自动缩容，GPU 节点空闲 30 分钟后自动释放。

## 自动分诊流程

AI Agent 接收 GPU 闲置告警后执行以下自动化分诊：

1. **闲置判定**：通过 VM 查询目标 GPU 节点近 24h/72h 利用率均值和分布，结合 `nvidia-smi` 实时采样，判定闲置等级（轻度/中度/重度/僵尸）。
2. **成本量化**：调用计费 API 查询该 GPU 实例近 30 天计费流水，计算日均成本和累计浪费金额，生成成本报告。
3. **Pod 关联分析**：查询 GPU 节点上所有 Pod 列表和状态，确认是否有活跃任务，区分「有 Pod 但闲置」和「无 Pod 纯僵尸」。
4. **租户通知**：通过钉钉向 GPU 实例所属租户发送确认通知，包含利用率数据、成本信息和释放建议，等待 24 小时反馈。
5. **自动回收**：超时未反馈的僵尸实例执行自动回收流程：cordon → drain → 释放实例 → 停止计费。
6. **报表更新**：处置完成后通过 XXL-JOB 同步 GPU 利用率和成本数据至 MongoDB，更新 POC2/POC4/POC7/POC15 算力成本报表。

## 处置建议

1. 配置 GPU 闲置告警：利用率 <5% 持续 12h 触发钉钉通知，<1% 持续 72h 自动回收。
2. Notebook Pod 配置空闲超时自动终止策略，30 分钟无活动自动停止 GPU 占用。
3. 训练任务 Pod 配置 `resources.limits.nvidia.com/gpu`，任务完成后自动释放 GPU 资源。
4. 配置 Cluster Autoscaler 自动缩容，GPU 节点空闲 30 分钟后自动释放。
5. 建立 GPU 成本日报机制，XXL-JOB 每日生成 GPU 利用率与成本关联报表，推送至管理层。
6. 推行 GPU 共享调度（MIG/MPS），多个轻量推理任务共享单张 GPU，提升整体利用率。

## 预防措施

1. **GPU 利用率基线**：为 aicp-notebook/aicp-job/aicp-isvc 三类产品设定 GPU 利用率基线，notebook 基线 15%、job 基线 60%、isvc 基线 30%，低于基线触发告警。
2. **成本告警联动**：GPU 利用率与计费系统联动，当单卡日均成本超过阈值但利用率低于基线时，自动触发成本优化告警。
3. **自动释放策略**：所有 GPU 实例配置最大存活时间（notebook 8h、job 24h），超时自动释放，需延期通过审批流程。
4. **GPU 配额治理**：按租户设定 GPU 配额上限，配额使用率与利用率挂钩，低利用率租户自动降低配额。
5. **巡检报表**：XXL-JOB 每 3 小时同步 GPU 利用率至 MongoDB，每日生成算力成本优化报表，识别持续闲置实例。

## 相关 PromQL 速查

```promql
# GPU 利用率（24h 均值）
avg_over_time(DCGM_FI_DEV_GPU_UTIL{node="$gpu_node"}[24h])

# GPU 显存使用率
DCGM_FI_DEV_FB_USED / (DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE)

# NPU 利用率（昇腾）
avg_over_time(npudevice_duty{instance="$npu_node"}[24h])

# GPU 卡时统计
sum(GPU_ALLOCATED) / 60

# GPU 闲置率（集群维度）
count(avg_over_time(DCGM_FI_DEV_GPU_UTIL[24h]) < 5) / count(DCGM_FI_DEV_GPU_UTIL)

# GPU 僵尸实例检测（72h 利用率 0%）
count_over_time(DCGM_FI_DEV_GPU_UTIL < 1[72h]) / count_over_time(DCGM_FI_DEV_GPU_UTIL[72h])

# 单卡日均成本关联
avg_over_time(DCGM_FI_DEV_GPU_UTIL[24h]) * on(node) group_left COST_PER_GPU_HOUR

# GPU 节点 Pod 数量
count(kube_pod_info{node=~"gpu-node.*"}) by (node)
```
