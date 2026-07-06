# GPU OOM 告警处理（AICP 算力场景）

## 告警规则

告警名称：`GPUMemoryExhausted`

PromQL 表达式：

```promql
# GPU 显存使用率（核心告警）
DCGM_FI_DEV_FB_USED{gpu=~".+",product_type=~"aicp-notebook|aicp-job|aicp-isvc"} / DCGM_FI_DEV_FB_TOTAL{gpu=~".+",product_type=~"aicp-notebook|aicp-job|aicp-isvc"} > 0.95

# GPU 利用率持续 100%（辅助告警）
max_over_time(DCGM_FI_DEV_GPU_UTIL{product_type=~"aicp-notebook|aicp-job|aicp-isvc"}[10m]) == 100

# NPU 利用率超限（华为昇腾场景）
npu_utilization_rate{device_id=~".+",product_type=~"aicp-notebook|aicp-job|aicp-isvc"} > 0.95
```

指标说明：`DCGM_FI_DEV_FB_USED` 是 NVIDIA DCGM（Data Center GPU Manager）采集的 GPU 已用显存（字节），`DCGM_FI_DEV_FB_TOTAL` 是显存总量，两者之比为显存使用率。`gpu` 标签标识 GPU 卡号，`product_type` 标签限定 AICP 三类产品（aicp-notebook/aicp-job/aicp-isvc）。`DCGM_FI_DEV_GPU_UTIL` 是 GPU 计算利用率百分比，`max_over_time(...[10m])` 确认 10 分钟内持续 100%。`npu_utilization_rate` 是华为昇腾 NPU 利用率，NPU 利用率计算公式为 cards x usage_rate / 60。

触发条件：GPU 显存使用率超过 95% 或 GPU 利用率持续 100% 超过 10 分钟。该告警覆盖 AICP 算力平台的 aicp-notebook、aicp-job、aicp-isvc 三类 AI 计算产品，同时关注 NPU 利用率指标（华为昇腾场景）。GPU 卡时统计公式为 cards/60，数据通过 dispatch（9091）→ cspm → adapter 采集写入 VM，XXL-JOB 每 3 小时同步至 MongoDB 生成用户维度算力使用报表。

## 告警分级

| 级别 | 阈值条件 | 响应时效 | 通知方式 |
|------|---------|---------|---------|
| P0 | GPU OOM 已触发，训练/推理任务崩溃 | 5 分钟内 | 电话 + 钉钉 + 短信 |
| P1 | 显存使用率 > 95% 或利用率 100% 持续 10min | 15 分钟内 | 电话 + 钉钉告警卡片 |
| P2 | 显存使用率 > 90% 或利用率 > 90% 持续 15min | 30 分钟内 | 钉钉群机器人推送 |
| P3 | 显存使用率 > 85%（趋势预警）或卡时用量 > 80% 月度配额 | 巡检处理 | 钉钉群消息 |

## 影响评估

GPU OOM 直接影响 AICP 算力平台：训练任务中断导致 checkpoint 丢失、推理服务返回 OOM 错误影响用户体验、显存碎片化导致后续任务启动失败、GPU 卡时统计（cards/60）超标触发配额熔断。若发生在 aicp-job 训练任务，可能损失数小时训练成果；若发生在 aicp-isvc 推理服务，直接影响线上 AI 接口可用性；若发生在 aicp-notebook 开发环境，用户开发进程中断。

## 关联组件

- **aicp-notebook**：交互式开发环境，用户自行配置 batch_size 和模型，OOM 高发
- **aicp-job**：批量训练任务，分布式训练多卡通信叠加显存压力
- **aicp-isvc**：推理服务（InferenceService），模型加载和并发推理消耗显存
- **NVIDIA DCGM Exporter**：GPU 指标采集组件，提供 DCGM_FI 系列 200+ GPU 指标
- **华为昇腾 NPU**：NPU 利用率计算公式 cards x usage_rate / 60，高利用率叠加大 batch_size 触发显存上限
- **VictoriaMetrics**：存储 GPU/NPU 指标，通过 `/v1/query/complex` 提供模板变量查询
- **XXL-JOB**：每 3 小时同步 VM GPU/NPU 数据至 MongoDB，生成用户维度卡时报表
- **dispatch（9091）→ cspm → adapter**：采集链路，GPU 指标通过该链路写入 VM

## 排障步骤

1. **查 GPU 指标**：通过 VM `/v1/query/complex` 接口传入 DCGM 系列 PromQL 模板变量，查询 GPU 显存使用率、利用率、温度等指标。PromQL：`DCGM_FI_DEV_FB_USED{gpu="$gpu"} / DCGM_FI_DEV_FB_TOTAL{gpu="$gpu"}`。确认告警 GPU 卡号、所在节点和产品类型（aicp-notebook/aicp-job/aicp-isvc）。
2. **确认占用 Pod/进程**：执行 `kubectl describe node <node> | grep -A5 nvidia.com/gpu` 查看 GPU 分配情况，或登录节点执行 `nvidia-smi` 确认进程 PID 和显存占用详情。通过 `nvidia-smi --query-compute-apps=pid,gpu_uuid,used_memory --format=csv` 精确定位占用进程。
3. **查训练任务配置**：检查任务 YAML 中的 `batch_size`、模型大小、梯度累积步数、`gradient_accumulation_steps` 等参数。对比模型参数量与显存容量是否匹配。8B 参数模型 FP32 约需 32GB 显存，FP16 约需 16GB。
4. **检查显存碎片**：执行 `nvidia-smi --query-gpu=memory.total,memory.used,memory.free --format=csv` 查看显存分布。若 `memory.free` 远小于 `memory.total - memory.used`，说明存在显存碎片化，需重启 GPU 进程释放。
5. **查卡时统计**：通过 MongoDB 报表数据确认用户月度 GPU 卡时（cards/60）是否超标。通过 `/v2/commonQuery` 查询 VM 中的卡时指标，评估是否需要触发配额熔断机制。NPU 场景查询 NPU 利用率（cards x usage_rate / 60）。
6. **查 GPU 温度与降频**：通过 `/v2/oneAgg` 查询 `DCGM_FI_DEV_GPU_TEMP`，若温度超过 85C 可能触发热降频，导致计算效率下降和显存访问异常。同时查询 `DCGM_FI_DEV_CLOCK_THROTTLE_REASONS` 确认是否发生降频。
7. **查分布式训练通信**：若为 aicp-job 多卡训练，检查 NCCL 通信指标。多卡 AllReduce 通信缓冲区占用显存，`NCCL_BUFFER_SIZE` 配置过大可能导致显存不足。

## 关键 PromQL

```promql
# 1. GPU 显存使用率——核心指标
# DCGM_FI_DEV_FB_USED：已用显存（字节），DCGM_FI_DEV_FB_TOTAL：显存总量
DCGM_FI_DEV_FB_USED{gpu="$gpu",product_type="$pt"} / DCGM_FI_DEV_FB_TOTAL{gpu="$gpu",product_type="$pt"}

# 2. GPU 利用率——计算压力
# DCGM_FI_DEV_GPU_UTIL：GPU 计算利用率百分比（0-100）
DCGM_FI_DEV_GPU_UTIL{gpu="$gpu"}

# 3. NPU 利用率（华为昇腾）——异构算力监控
# npu_utilization_rate：NPU 利用率，计算公式 cards x usage_rate / 60
npu_utilization_rate{device_id="$npu",product_type="$pt"}

# 4. GPU 温度——热降频排查
# DCGM_FI_DEV_GPU_TEMP：GPU 温度（摄氏度），超 85C 触发热降频
DCGM_FI_DEV_GPU_TEMP{gpu="$gpu"}

# 5. GPU 卡时统计——配额管理
# GPU 卡时 = cards / 60（每小时每卡计 1 卡时）
sum by (user) (count by (user) (DCGM_FI_DEV_GPU_UTIL{product_type=~"aicp-.*"} > 0)) / 60

# 6. GPU 显存趋势——预测 OOM 时间
deriv(DCGM_FI_DEV_FB_USED{gpu="$gpu"}[1h])

# 7. GPU 降频事件——性能异常排查
# DCGM_FI_DEV_CLOCK_THROTTLE_REASONS：降频原因 bitmask
DCGM_FI_DEV_CLOCK_THROTTLE_REASONS{gpu="$gpu"}

# 8. GPU 功耗——能耗与负载关联
DCGM_FI_DEV_POWER_DRAW{gpu="$gpu"}
```

## 真实案例

**时间线**：2024 年 7 月，联通数科 AICP 平台 POC4 集群，aicp-notebook 开发环境。

**T+0min**：GPU OOM 告警触发，显存使用率 98%，P1 告警。用户在 Notebook 中启动 8B 参数模型推理任务时显存不足直接崩溃，`CUDA out of memory` 报错。

**T+5min**：值班人员通过 VM `/v1/query/complex` 查询 DCGM 指标，确认 GPU 卡号 GPU0、所在节点 `poc4-gpu-node-03`、产品类型 `aicp-notebook`。显存使用率 98%（30.7GB/32GB），利用率 100%。

**T+10min**：SSH 登录节点执行 `nvidia-smi`，确认进程 PID 28567 占用 30.7GB 显存。`nvidia-smi --query-compute-apps=pid,used_memory --format=csv` 精确定位该进程。

**T+15min**：检查任务配置，发现 `batch_size` 设置为 64，8B 参数模型 FP32 推理。8B 模型 FP32 参数约需 32GB（8B x 4 bytes），加上 batch_size=64 的激活值和 KV Cache，远超单卡 32GB 显存。

**T+20min**：通过 `/v2/commonQuery` 查询 MongoDB 报表数据，该用户月度 GPU 卡时（cards/60）已超标 120%。卡时统计通过 XXL-JOB 每 3 小时从 VM 同步至 MongoDB，数据准确。

**T+25min**：查询 `DCGM_FI_DEV_GPU_TEMP` 温度 78C（正常范围），`DCGM_FI_DEV_CLOCK_THROTTLE_REASONS` 为 0（无降频），排除热因素。查询 NPU 利用率指标（cards x usage_rate / 60），确认华为昇腾场景同样存在大 batch_size 触发显存上限的风险。

**根因**：用户在 aicp-notebook 中启动 8B 参数模型推理任务，`batch_size=64` 远超单卡 32GB 显存承载能力。8B 模型 FP32 仅参数即需 32GB，加上激活值和 KV Cache，显存严重不足。用户月度卡时已超标 120%，未触发配额熔断。

**处置**：（1）降低 `batch_size` 至 8，使用梯度累加补偿批次大小；（2）启用混合精度推理（FP16/BF16），显存占用减半至约 16GB；（3）通过 AICP 平台配额管理设置该用户月度卡时上限，超标自动熔断；（4）Notebook 环境增加显存预检机制。显存使用率于 T+35min 降至 52%。

## 自动分诊流程

AI Agent 接收 `GPUMemoryExhausted` 告警后执行以下自动化分诊：

1. **告警解析**：提取 `gpu`、`instance`、`product_type` 标签，判定 GPU 卡号、节点和产品线（aicp-notebook/aicp-job/aicp-isvc）。
2. **指标采集**：通过 VM `/v1/query/complex` 并行查询显存使用率、利用率、温度、降频事件、功耗，构建 GPU 健康度视图。
3. **进程定位**：自动 SSH 登录节点执行 `nvidia-smi --query-compute-apps`，定位占用 GPU 的进程 PID 和 Pod 名称。
4. **配置审计**：读取任务 YAML 中的 `batch_size`、模型参数量、精度模式，根据公式（参数量 x bytes + 激活值 + KV Cache）预估显存需求，判定配置是否合理。
5. **卡时核查**：通过 `/v2/commonQuery` 查询 MongoDB 报表，确认用户月度 GPU 卡时（cards/60）是否超标，超标则触发配额熔断建议。
6. **根因匹配**：基于规则引擎匹配常见模式——batch_size 过大（显存不足 + 利用率 100%）、显存碎片（free 远小于 total-used）、热降频（温度 > 85C）、多卡通信缓冲区溢出（NCCL AllReduce 失败）。
7. **处置建议**：生成处置卡片推送钉钉群，包含 batch_size 调整建议、混合精度开启命令、配额熔断策略，等待值班人员确认。

## 处置建议

1. 降低 `batch_size` 至合理范围（8B 模型建议 batch_size=8），使用梯度累加补偿批次大小。
2. 启用混合精度训练（FP16/BF16），显存占用减半。
3. 限制单用户 GPU 配额：通过 AICP 平台配额管理设置月度卡时上限，超标自动熔断。
4. Notebook 环境增加显存预检：任务启动前根据模型参数量和 batch_size 预估显存需求，超限拒绝启动。
5. 卡时统计通过 XXL-JOB 每 3 小时同步 VM 数据至 MongoDB，生成用户维度 GPU/NPU 使用报表。
6. GPU 显存使用率 90% 预警加 95% 紧急两级告警，提前介入避免 OOM。
7. 分布式训练任务检查 NCCL 通信缓冲区配置，避免通信缓冲区叠加显存溢出。

## 预防措施

1. **显存预检机制**：aicp-notebook/aicp-job 任务启动前根据模型参数量、batch_size、精度模式预估显存需求，超出单卡容量自动拒绝或建议多卡分布式。
2. **配额熔断**：AICP 平台设置用户月度 GPU 卡时上限（cards/60），超标自动熔断。NPU 场景同理（cards x usage_rate / 60）。
3. **混合精度默认化**：训练/推理任务默认启用 FP16/BF16 混合精度，显存占用减半，AICP 平台在任务提交界面默认勾选。
4. **卡时报表巡检**：XXL-JOB 每 3 小时同步 VM GPU/NPU 指标至 MongoDB，生成 POC2/POC4/POC7/POC15 用户维度卡时报表，卡时 > 80% 月度配额触发 P3 预警。
5. **GPU 健康度基线**：建立显存使用率、利用率、温度基线，`deriv` 超阈值自动告警。定期执行 `nvidia-smi --query-gpu=memory.total,memory.used,memory.free` 检测显存碎片化。

## 相关 PromQL 速查

```promql
# GPU 显存使用率
DCGM_FI_DEV_FB_USED{gpu="$gpu"} / DCGM_FI_DEV_FB_TOTAL{gpu="$gpu"}

# GPU 利用率
DCGM_FI_DEV_GPU_UTIL{gpu="$gpu"}

# NPU 利用率（华为昇腾）
npu_utilization_rate{device_id="$npu"}

# GPU 温度
DCGM_FI_DEV_GPU_TEMP{gpu="$gpu"}

# GPU 卡时统计（cards/60）
sum by (user) (count by (user) (DCGM_FI_DEV_GPU_UTIL > 0)) / 60

# GPU 显存 1h 趋势
deriv(DCGM_FI_DEV_FB_USED{gpu="$gpu"}[1h])

# GPU 降频事件
DCGM_FI_DEV_CLOCK_THROTTLE_REASONS{gpu="$gpu"}

# GPU 功耗
DCGM_FI_DEV_POWER_DRAW{gpu="$gpu"}

# NPU 卡时统计（cards x usage_rate / 60）
sum by (user) (count by (user) (npu_utilization_rate > 0) * npu_utilization_rate) / 60
```
