# 容量规划 SOP

## 概述
本文档规范联通数科云管平台（MSP）及 AICP 算力平台的容量评估、监控、扩容决策流程，覆盖 CPU、内存、磁盘、网络、GPU 五大维度，适配多云纳管与政务云项目场景。MSP 平台纳管 6 家云厂商（天翼云/联通云/华为云/阿里云/移动云），4 个政务云项目（湖南/西安/大连/烟台），4 个 POC 环境（POC2: 172.25.1.250 / POC4: 172.25.4.250 / POC7: 172.25.5.222 / POC15: 172.25.35.250）。CMDB 资源同步与计费系统（EAV 模型, Snowflake ID）为容量数据提供底层数据支撑。

## 角色与职责

| 角色 | 职责 |
|------|------|
| SRE 工程师 | 容量监控告警规则维护，周报输出，趋势分析 |
| 运维负责人 | 扩容方案审批，跨云资源调度决策 |
| 模块负责人 | 模块级容量需求评估，requests/limits 调整建议 |
| AICP 平台管理员 | GPU 资源容量规划，租户配额管理，卡时统计 |
| 计费系统管理员 | CMDB 资源同步监控，EAV 模型数据准确性校验 |
| 云厂商对接人 | 各云厂商资源池剩余容量查询，配额申请 |

## 容量评估维度

| 维度 | 关键指标 | 预警阈值 | 危险阈值 |
|------|----------|----------|----------|
| CPU | node_cpu_usage | >70% | >85% |
| 内存 | node_memory_usage | >70% | >90% |
| 磁盘 | node_filesystem_usage | >75% | >90% |
| 网络 | node_network_bandwidth | >70% | >90% |
| GPU | GPU 利用率/显存 | >75% | >95% |

## 容量监控指标

通过 Prometheus 采集以下核心指标：

```promql
# 节点 CPU 使用率
100 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) by (instance) * 100
# 节点内存使用率
(1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100
# 磁盘使用率
(1 - node_filesystem_avail / node_filesystem_size) * 100
# GPU 利用率（NVIDIA DCGM Exporter）
DCGM_FI_DEV_GPU_UTIL
# GPU 显存使用率
DCGM_FI_DEV_FB_USED / DCGM_FI_DEV_FB_TOTAL * 100
# Pod 级 CPU 使用率
sum(rate(container_cpu_usage_seconds_total{namespace="msp-prod"}[5m])) by (pod) * 1000
```

## 扩容决策流程

### 流程总览
预警触发（指标 >70% 持续 15 分钟） → 趋势评估（近 7 天数据分析） → 方案制定（垂直/水平/节点扩容） → 审批执行 → 效果验证

### 各步骤详解

**Step 1 预警触发**：指标持续 >70% 超过 15 分钟，Prometheus 告警推送钉钉群。

**Step 2 趋势评估**：
```bash
# 查询近 7 天 CPU 趋势
# PromQL: avg_over_time(node_cpu_usage[7d])
```
分析是否为突发增长（如批量任务）或持续增长（如用户量上升），判断扩容紧迫度。

**Step 3 方案制定**：
- **垂直扩容**：调整 Pod requests/limits，适合单 Pod 瓶颈。通过 Helm values.yaml 修改后 `helm upgrade`
- **水平扩容**：`kubectl -n msp-prod scale deployment <deploy> --replicas=N`，适合负载均衡场景
- **节点扩容**：增加 K8s worker 节点，适合节点级瓶颈。须评估节点规格与云厂商可用区

**Step 4 执行扩容**：
```bash
# 水平扩容（快速）
kubectl -n msp-prod scale deployment cspm --replicas=5
# Helm values 扩容（推荐，配置可追溯）
helm upgrade <release> <chart> -n msp-prod -f values.yaml --set replicaCount=5
```

**Step 5 效果验证**：扩容后监控指标是否回落至 70% 以下，业务响应时间是否改善。`kubectl -n msp-prod top pods` 确认负载分布均匀。

## 多云资源纳管场景

MSP 平台纳管 6 家云厂商（天翼云/联通云/华为云/阿里云/移动云），容量规划需：

- **资源池对比**：定期对比各云厂商资源池剩余容量，输出容量周报。CMDB 同步各云厂商资源数据
- **租户配额管理**：按租户维度设置 CPU/内存/磁盘/GPU 配额上限，防止资源争抢
- **跨云调度**：某云厂商资源不足时，可调度至其他云厂商资源池
- **成本优化**：对比各云厂商单价，优先使用性价比高的资源池。计费系统基于 EAV 模型记录资源用量

## AICP 算力容量规划

AICP 场景需额外关注 GPU 资源容量：

- **GPU 卡时统计**：按租户维度统计 GPU 卡时消耗，输出月度算力报表。数据同步至计费系统（Snowflake ID 唯一标识）
- **副本数乘数计算**：模型推理并发量 = 副本数 × 单副本 QPS，根据峰值 QPS 计算所需副本数
- **显存评估**：模型加载显存 + 推理动态显存 ≤ 单卡显存 × 80%（预留安全边界）
- **租户配额**：按租户分配 GPU 卡数上限，防止资源争抢
- **GPU 类型区分**：NVIDIA A100/H800 算力不同，容量评估须按 GPU 型号分别统计

## 真实案例

**时间**：2025 年 9 月
**事件**：AICP 平台某租户 GPU 显存使用率持续 >90%，导致模型推理 OOM。

**时间线**：
- 09:01 — Prometheus 告警：租户 GPU 显存使用率 92%（DCGM_FI_DEV_FB_USED/DCGM_FI_DEV_FB_TOTAL）
- 09:05 — SRE 工程师确认告警，查看 GPU 节点监控
- 09:10 — 排查发现租户部署的 7B 模型副本数为 4，单卡 A100（80GB）显存已接近满载
- 09:15 — 评估方案：方案 A 增加 GPU 节点；方案 B 模型量化降显存
- 09:20 — 决策：方案 B 优先（快速生效），方案 A 同步推进（长期容量）
- 09:30 — 将单副本模型从 7B 量化为 4B，显存占用减半
- 09:45 — 显存使用率降至 55%，推理 OOM 问题消除
- 10:00 — 申请增加 2 张 A100 GPU 节点扩容
- 次日 — GPU 节点扩容完成，显存使用率降至 65%，安全边界充足
- 周报 — 输出 AICP GPU 容量周报，建议各租户显存使用率控制在 80% 以下

**经验教训**：
1. AICP 租户 GPU 配额须设置显存使用率 80% 硬限制，超限自动拒绝新部署
2. 模型量化为有效的应急降显存手段，须在租户文档中提供量化指南
3. GPU 容量规划须考虑峰值并发，不能仅看平均使用率
4. CMDB 中 GPU 资源数据须与实际节点配置定期校验，确保容量评估准确
5. 计费系统（EAV 模型）须准确记录 GPU 卡时消耗，为容量扩容决策提供数据支撑

## 检查清单
- [ ] 五大维度（CPU/内存/磁盘/网络/GPU）监控告警已配置
- [ ] 预警阈值（70%/75%）与危险阈值（85%/90%/95%）已设定并定期校准
- [ ] 扩容决策流程已文档化且团队已培训
- [ ] 多云资源池容量周报已按时输出
- [ ] 租户级配额已配置并定期审查（至少每月一次）
- [ ] GPU 卡时统计月度报表已建立
- [ ] AICP 租户 GPU 配额上限已设置（显存 ≤80%）
- [ ] CMDB 资源同步任务运行正常（EAV 模型数据准确）
- [ ] 计费系统 Snowflake ID 唯一性校验通过
- [ ] 扩容操作后效果验证已完成并记录

## 注意事项
1. 容量扩容须遵循 POC 先行原则，新节点规格须先在 POC 环境验证 K8s 注册与调度正常。
2. 水平扩容前须确认 Service 负载均衡策略（如 session affinity）是否影响多副本分发。
3. GPU 节点扩容须确认驱动版本与 CUDA 版本兼容性，避免节点加入集群后容器无法调度。
4. 多云资源池容量周报须包含各云厂商配额上限与已用比例，不能仅看当前使用量。
5. 政务云项目容量规划受限于等保要求，跨云调度须先确认数据合规性。
6. 计费系统 EAV 模型数据须与 CMDB 定期对账，偏差超过 5% 须触发数据修复流程。
7. AICP GPU 容量评估须区分训练场景与推理场景，训练场景显存需求通常为推理的 3-5 倍。

## 工具与资源
- **Prometheus + Grafana**：node_* 与 DCGM_FI_* 指标采集与可视化
- **NVIDIA DCGM Exporter**：GPU 利用率与显存监控指标采集
- **kubectl top**：`kubectl -n msp-prod top pods/nodes` 实时资源使用查询
- **Helm CLI**：通过 values.yaml 调整 requests/limits/replicaCount 后 `helm upgrade`
- **CMDB 系统**：多云资源同步与纳管资源台账（EAV 模型）
- **计费系统**：Snowflake ID 唯一标识，GPU 卡时统计与资源计费
- **Ansible**：多节点批量扩容与配置同步
- **POC 环境集群**：POC2/POC4/POC7/POC15 用于扩容方案验证
- **各云厂商控制台**：天翼云/联通云/华为云/阿里云/移动云 资源池容量查询与配额申请
