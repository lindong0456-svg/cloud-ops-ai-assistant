# 应急预案 SOP

## 概述
本文档定义联通数科云管平台（MSP）生产环境应急响应流程与常见应急处置操作。故障级别判定与升级路径统一参照《故障分级标准 SOP》，本文档不再重复定义 P0-P3 级别。回滚操作的详细步骤参照《变更回滚 SOP》。本文档涵盖通用应急处置手段、政务云特殊场景预案及 AICP 算力场景应急处理。

## 角色与职责

| 角色 | 职责 |
|------|------|
| 值班运维工程师 | 首接告警，确认真实性，执行 P2/P3 应急处置 |
| 运维负责人 | P0/P1 应急指挥，组建应急小组，审批高风险操作 |
| 模块负责人 | 执行模块级应急处置（重启/回滚/限流/扩容） |
| 研发工程师 | 代码级应急修复，提供 hotfix patch |
| 驻场人员（政务云） | 现场物理网络确认，VPN/跳板机操作协助 |
| 架构师 | 跨模块影响评估，架构级决策（如 DNS 切流） |

## 应急响应流程

### 流程总览
告警触发 → 值班人员确认（5 分钟内） → 按故障分级启动响应（参照分级 SOP） → 组建应急小组 → 处置决策 → 执行处置 → 验证恢复 → 事后复盘

### 各步骤详解

**Step 1 告警触发**：Prometheus 告警或用户反馈触发，钉钉机器人推送告警信息。

**Step 2 值班人员确认**：5 分钟内确认告警真实性，排除误告警。确认方式：查看 Prometheus 原始指标 + kubectl 检查 Pod 状态。

**Step 3 启动响应**：依据《故障分级标准 SOP》判定的级别启动对应流程。P0 立即组建应急小组，P1 通知模块负责人，P2/P3 按常规流程处理。

**Step 4 组建应急小组**：运维 + 研发 + 架构师协同，建立钉钉应急群。P0 须在 15 分钟内完成小组组建并明确指挥人。

**Step 5 处置决策**：评估是否需要回滚、重启或扩容。决策须考虑：变更窗口内最近变更、当前 Pod 副本数、节点资源余量。

**Step 6 执行处置**：按决策方案执行操作（见下文常见处置操作）。

**Step 7 验证恢复**：监控指标与业务功能双重确认。`kubectl -n msp-prod get pods` 确认 Pod 全部 Running，健康检查接口返回 200。

**Step 8 事后复盘**：按故障级别复盘要求执行（P0: 48h, P1: 3 工作日, P2: 周会, P3: 月度汇总）。

## 常见应急处置操作

### 服务重启（Pod 强制重建）
```bash
# 单 Pod 强制重建
kubectl -n msp-prod delete pod <pod-name> --grace-period=0 --force
# 按 label 批量重启
kubectl -n msp-prod rollout restart deployment/<deploy-name>
```

### Helm 回滚（详见《变更回滚 SOP》）
```bash
# 查看历史版本
helm history <release-name> -n msp-prod
# 回滚至指定 revision
helm rollback <release-name> <revision-number> -n msp-prod
```

### K8s 扩容
```bash
# 水平扩容
kubectl -n msp-prod scale deployment <deploy> --replicas=5
# 通过 Helm values 调整（推荐）
helm upgrade <release> <chart> -n msp-prod -f values.yaml --set replicaCount=5
```

### Nginx 限流
```bash
# 编辑 Nginx gateway 配置增加 limit_req
kubectl -n msp-prod edit configmap nginx-gateway-config
# 或通过 Helm 修改 values.yaml 后 upgrade
# 校验并重载
kubectl -n msp-prod exec <nginx-pod> -- nginx -t
kubectl -n msp-prod exec <nginx-pod> -- nginx -s reload
```

### Nacos 配置热更新
在 Nacos 控制台（namespace prod: daa3b24c-c5b0-4524-8322-58910e7bb739）修改配置后，确认对应服务已感知配置变更（检查日志中 "Nacos config changed" 记录）。

### DNS 切流（跨可用区故障切换）
```bash
# 修改 CoreDNS ConfigMap 将流量切到备用可用区
kubectl -n kube-system edit configmap coredns
# 或修改 Nginx gateway upstream 指向备用节点池
kubectl -n msp-prod edit configmap nginx-gateway-config
# 验证切换效果
kubectl -n msp-prod exec <pod> -- curl -s -o /dev/null -w "%{http_code}" http://dispatch:9091/health
```
DNS 切流须提前在 POC 环境验证，确认切换后服务可正常访问。切流后密切关注 VictoriaMetrics 指标，确认流量已完全切换。

### Kafka 消息积压处理
```bash
# 查看 consumer group 积压情况
kubectl -n msp-prod exec <kafka-pod> -- kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group lingyun-mysql-to-mongo
# 临时增加消费者副本
kubectl -n msp-prod scale deployment <consumer-deploy> --replicas=5
# 确认积压下降后恢复原副本数
```
处理前须确认是否可安全丢弃过期消息。计费消息（billing_stream）不可丢弃，须等待消费完成；监控同步消息（VM to MongoDB）可按需跳过过期数据。

### AICP GPU 节点故障处理
```bash
# 确认 GPU 节点状态
kubectl get nodes -l nvidia.com/gpu.present=true -o wide
# 检查 GPU Pod 分布
kubectl -n msp-prod get pods -o wide | grep gpu
# 驱逐故障节点上的 GPU Pod（谨慎操作，确认无运行中训练任务）
kubectl drain <gpu-node> --ignore-daemonsets --delete-emptydir-data
```
GPU 节点故障前须检查是否有 AICP 训练任务（Job/ISVC）在运行。若有，须通过 AICP 平台通知用户任务中断，并记录卡时损失用于后续补偿核算。NPU 场景（华为昇腾）处理流程相同，须额外检查 `npu-manager` 组件状态。

## 政务云特殊预案

政务云环境（湖南/西安/大连/烟台）采用 VPN → 跳板机 → 虚机三级跳转访问模式。

### VPN 断连应急流程
确认断连范围 → 联系驻场人员确认物理网络 → 若短期无法恢复则建立 SSH 隧道 → 通过隧道执行应急操作 → VPN 恢复后验证全链路

```bash
# 建立 SSH 隧道维持 MongoDB 连接
ssh -L 27017:<内网MongoDB>:27017 <跳板机用户>@<跳板机IP>
# 建立 autossh 自动重连隧道
autossh -M 0 -o "ServerAliveInterval 30" -o "ServerAliveCountMax 3" \
  -L 27017:<内网MongoDB>:27017 <跳板机用户>@<跳板机IP>
```

## 真实案例

**时间**：2025 年 8 月
**事件**：湖南政务云项目 MongoDB 连接隧道因 VPN 抖动断连，cspm 服务数据读取失败告警。

**时间线**：
- 02:15 — 钉钉告警：cspm 服务 MongoDB 连接超时
- 02:18 — 值班人员确认告警，判定 P1 级别（影响湖南政务云项目）
- 02:20 — 排查发现 VPN 隧道抖动导致 SSH 端口转发中断
- 02:25 — 联系湖南驻场人员确认物理网络正常，VPN 网关偶发抖动
- 02:30 — 通过跳板机重建 SSH 隧道恢复 MongoDB 连接
- 02:35 — cspm 服务恢复，告警消除
- 02:40 — 在 Nacos 中调整 MongoDB 连接超时参数为指数退避模式
- 03:00 — 在跳板机上配置 autossh 保持隧道自动重连
- 次日 — 复盘报告完成，推动 VPN 网关稳定性优化

**经验教训**：
1. 政务云 VPN 隧道须配置 autossh 自动重连，不能依赖手动恢复
2. MongoDB 连接参数须配置指数退避重试，避免 VPN 抖动时连接雪崩
3. 政务云应急须建立 7x24 驻场联络机制，确保物理网络问题快速响应
4. P1 级别从告警到恢复仅 20 分钟，应急流程有效

## 检查清单
- [ ] 应急响应流程是否按故障级别启动
- [ ] 应急小组是否已组建并建立钉钉应急群
- [ ] 处置操作是否已确认风险并审批（高风险操作须运维负责人审批）
- [ ] 政务云 VPN 断连预案是否已纳入定期演练
- [ ] SSH 隧道方案是否可用且 autossh 已配置
- [ ] 服务恢复后是否完成双重验证（监控 + 业务功能）
- [ ] 复盘报告是否在规定时间内完成
- [ ] 改进措施是否已跟踪闭环

## 注意事项
1. P0 级别应急处置优先恢复服务，可在根因未完全明确时先执行回滚或重启，但须保留现场证据（日志截图、Pod describe 输出）。
2. 政务云环境操作须严格遵守等保要求，所有操作须通过跳板机执行并留存审计日志。
3. Nginx 限流为临时手段，须在限流后尽快定位根因并修复，避免长期限流影响业务。
4. DNS 切流操作须提前在 POC 环境验证，确认切换后服务可正常访问。
5. AICP GPU 节点故障时，须先检查是否有模型推理任务在运行，避免直接重启导致推理中断。
6. Kafka 消息积压应急处置时，须先确认是否可安全丢弃过期消息，再决定是否清理队列。

## 工具与资源
- **kubectl**：Pod 重建、扩容、滚动重启
- **Helm CLI**：`helm rollback` 快速回滚（详见《变更回滚 SOP》）
- **Nacos 控制台**：配置热更新，namespace prod（daa3b24c-c5b0-4524-8322-58910e7bb739）
- **Ansible**：多节点批量操作，镜像管理与配置同步
- **autossh**：政务云跳板机 SSH 隧道自动重连
- **Prometheus + Grafana**：实时监控指标验证恢复状态
- **钉钉应急群**：P0/P1 应急沟通主渠道，须在群内实时同步处置进展
- **XXL-JOB 控制台**（8013 端口）：定时任务应急暂停与恢复
