# 排障流程模板 SOP

## 概述
本文档规范联通数科云管平台（MSP）生产环境故障排查的标准流程与工具使用方法，确保排障过程高效、可追溯。平台采用 dispatch（9091）→ cspm → adapter 三层架构，排障遵循从网关层往业务层逐层下钻的思路。故障级别判定参照《故障分级标准 SOP》，应急处置操作参照《应急预案 SOP》，回滚操作参照《变更回滚 SOP》。

## 角色与职责

| 角色 | 职责 |
|------|------|
| 值班运维工程师 | 执行标准排障流程 Step 1-4，收集信息并初步定位 |
| 模块负责人 | 执行 Step 5-7 深入排查与根因修复，提供模块级专业知识 |
| SRE 工程师 | 监控指标分析与 PromQL 查询，辅助趋势判断 |
| 研发工程师 | 协助代码级根因分析，提供修复 patch |
| 运维负责人 | 协调跨模块资源，审批高风险处置操作 |

## 标准排障流程

### 流程总览
告警触发 → 现象确认 → 影响评估（参照故障分级标准 SOP） → 告警分级 → 初步定位（dispatch 网关层） → 深入排查（cspm → adapter 逐层下钻） → 临时处置 → 根因修复 → 验证恢复 → 复盘总结

### 各步骤详解

**Step 1 现象确认**：明确故障表现，确认是否为真实故障而非用户误操作。检查 Prometheus 告警面板，确认告警是否自动恢复。

**Step 2 影响评估**：按《故障分级标准 SOP》判定 P0-P3，确定影响范围（受影响云厂商、项目环境、用户群体）。

**Step 3 告警分级**：根据判定级别触发对应响应流程。P0/P1 立即组建应急小组，P2/P3 按常规流程处理。

**Step 4 初步定位 — dispatch 网关层**：
```bash
# 检查 dispatch 服务端点
kubectl -n msp-prod get svc dispatch
# 检查 dispatch Pod 状态
kubectl -n msp-prod get pods -l app=dispatch -o wide
# 查看 dispatch 日志
kubectl -n msp-prod logs -l app=dispatch --tail=200
# 检查 Nginx gateway 配置
kubectl -n msp-prod exec <nginx-pod> -- nginx -T | grep upstream
```

**Step 5 深入排查 — cspm 服务层**：
```bash
# cspm Pod 状态与资源使用
kubectl -n msp-prod get pods -l app=cspm
kubectl -n msp-prod top pods -l app=cspm
# cspm 日志中的 ERROR/Exception
kubectl -n msp-prod logs -l app=cspm --tail=500 | grep -i "error\|exception"
# Nacos 中 cspm 配置是否正常
# 检查 Nacos namespace prod: daa3b24c-c5b0-4524-8322-58910e7bb739
```

**Step 6 深入排查 — adapter 适配层**：
```bash
# 查看各云厂商 adapter 状态
kubectl -n msp-prod get pods | grep adapter
# 查看 adapter 调用云厂商 API 的日志
kubectl -n msp-prod logs <adapter-pod> --tail=300
# 检查 adapter 连接云厂商 API 的网络连通性
kubectl -n msp-prod exec <adapter-pod> -- curl -s -o /dev/null -w "%{http_code}" <cloud-api-endpoint>
```

**Step 7 临时处置**：必要时执行服务重启、回滚或限流等应急操作（详见《应急预案 SOP》）。

**Step 8 根因修复**：定位根因后实施永久修复，修改代码或配置后走 CI/CD 流水线重新部署。

**Step 9 验证恢复**：
```bash
# 滚动更新状态
kubectl -n msp-prod rollout status deployment/<deploy>
# 健康检查
kubectl -n msp-prod exec <pod> -- curl -s http://localhost:<port>/actuator/health
```
确认监控指标回归基线，业务功能验证关键链路走通。

**Step 10 复盘总结**：输出复盘报告，补充至 RAG 知识库，更新排障 wiki。

### 常见问题快速定位表

| 现象 | 可能原因 | 快速验证命令 | 参考文档 |
|------|---------|-------------|---------|
| Pod Pending | 资源不足/调度约束 | `kubectl describe pod <pod> -n msp-prod \| grep -A10 Events` | K8s Pod Pending 排障 |
| Pod CrashLoopBackOff | 应用启动失败/配置错误 | `kubectl logs <pod> -n msp-prod --previous` | K8s CrashLoopBackOff 排障 |
| Pod Evicted | 节点磁盘/内存压力 | `kubectl describe pod <pod> -n msp-prod \| grep Reason` | K8s Pod Evicted 排障 |
| 节点 NotReady | kubelet/容器运行时异常 | `kubectl describe node <node> \| grep -A5 Conditions` | K8s 节点 NotReady 排障 |
| Pod OOMKilled | 内存 limit 不足/JVM 配置 | `kubectl describe pod <pod> -n msp-prod \| grep "Last State"` | K8s OOMKilled 排障 |
| 接口 502 | Nginx upstream 异常/dispatch 宕机 | `kubectl -n msp-prod exec <nginx-pod> -- nginx -T \| grep upstream` | Docker 容器无法启动排障 |
| 接口超时 | adapter 云厂商 API 限流/网络抖动 | `kubectl -n msp-prod logs <adapter-pod> --tail=100 \| grep -i timeout` | 监控告警-网络抖动 |
| CPU 持续高 | 指标全量扫描/内存泄漏 | `rate(container_cpu_usage_seconds_total{namespace="msp-prod"}[5m])` | 监控告警-CPU 高 |
| 磁盘满 | 日志未轮转/镜像堆积 | `docker system df` + `du -sh /var/log/containerd/` | Docker 磁盘满排障 |
| GPU OOM | batch_size 过大/显存不足 | `nvidia-smi` + `DCGM_FI_DEV_FB_USED` | 监控告警-GPU OOM |

### 常用 PromQL 排障查询

```promql
# 1. 查看命名空间所有 Pod 的 CPU 使用率
sum(rate(container_cpu_usage_seconds_total{namespace="msp-prod"}[5m])) by (pod)

# 2. 查看命名空间所有 Pod 的内存使用量
container_memory_working_set_bytes{namespace="msp-prod"}

# 3. 查看 Pod 重启次数
kube_pod_container_status_restarts_total{namespace="msp-prod"}

# 4. 查看节点磁盘使用率
1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}

# 5. 查看 dispatch 网关 HTTP 5xx 速率
rate(http_requests_total{namespace="msp-prod",status=~"5.."}[5m])

# 6. 查看 Pod 状态分布
count(kube_pod_status_phase{namespace="msp-prod"}) by (phase)

# 7. 查看节点资源分配率
sum(kube_pod_container_resource_requests{resource="cpu"}) by (node) / kube_node_status_allocatable{resource="cpu"}

# 8. 查看 Kafka 消费者积压
kafka_consumer_lag{consumer_group="lingyun-mysql-to-mongo"}

# 9. 查看 VM 查询接口响应时间 P99
histogram_quantile(0.99, rate(vm_request_duration_seconds_bucket[5m]))

# 10. 查看 GPU 显存使用率
DCGM_FI_DEV_FB_USED / DCGM_FI_DEV_FB_TOTAL
```

### 三层架构排障决策树

```
故障发生
  ├── 是否 dispatch 网关层故障？
  │     ├── 是 → 检查 Nginx config / dispatch Pod 状态 / 端口 9091
  │     └── 否 → 进入 cspm 层检查
  │
  ├── 是否 cspm 业务层故障？
  │     ├── 是 → 检查 cspm Pod 状态 / Nacos 配置 / MongoDB 连接 / 日志异常
  │     └── 否 → 进入 adapter 层检查
  │
  └── 是否 adapter 适配层故障？
        ├── 是 → 检查 adapter Pod 状态 / 云厂商 API 连通性 / AK-SK 有效性
        └── 否 → 检查基础设施（K8s 节点 / 网络 / 存储 / 数据库）
```

三层架构排障的核心思路：从上游往下游逐层排查。dispatch 是入口层，如果 dispatch 不通，下游 cspm 和 adapter 无法被外部访问；如果 dispatch 正常但 cspm 异常，业务逻辑无法执行；如果 cspm 正常但 adapter 异常，特定云厂商操作失败。通过逐层缩小范围，可以快速定位故障层级。

## 信息收集模板

- **故障时间**：____年__月__日 __时__分 发现
- **影响范围**：受影响服务 / 云厂商 / 项目环境（POC2/POC4/POC7/POC15 或政务云）
- **告警信息**：Prometheus 告警截图、钉钉告警记录
- **最近变更**：Helm upgrade / Nginx 配置变更 / 镜像更新记录（含分支名如 `7.7.0-patch23-20251029_cloud`）
- **日志关键错误**：相关 Pod 日志中的 ERROR/Exception 堆栈
- **三层排查记录**：dispatch / cspm / adapter 各层检查结果

## 真实案例

**时间**：2025 年 11 月
**事件**：联通云 adapter 操作超时，导致联通云相关资源操作全部失败。

**时间线**：
- 09:15 — 钉钉告警：联通云 adapter 调用超时率 >50%
- 09:17 — 值班人员确认告警真实性，判定 P1 级别
- 09:20 — Step 4：确认 dispatch 网关正常（`kubectl -n msp-prod get svc dispatch` 端点健康）
- 09:25 — Step 5：cspm 服务日志无异常，cspm → adapter 调用链路正常
- 09:30 — Step 6：adapter 层日志显示联通云 API 返回 429 限流
- 09:35 — 确认根因：联通云 API 临时限流，adapter 未设置超时重试机制
- 09:40 — 临时处置：在 Nacos 中调整 adapter 超时参数为 30s，增加指数退避重试
- 10:00 — 联通云 API 限流解除，adapter 重试逻辑生效，操作恢复正常
- 次日 — 修复代码增加指数退避重试逻辑，通过 CI/CD 部署至生产

**经验教训**：
1. 各云厂商 adapter 须配置超时重试机制，默认超时 30s，指数退避重试 3 次
2. 云厂商 API 限流场景需在 adapter 层增加限流感知与自动降级
3. 三层逐层下钻排障方法有效，从告警到根因定位仅 15 分钟

## 检查清单
- [ ] 故障现象已确认并记录（含告警截图）
- [ ] 故障级别已参照《故障分级标准 SOP》判定
- [ ] 三层架构逐层排查已完成（dispatch → cspm → adapter）
- [ ] 相关日志与监控截图已收集归档
- [ ] 信息收集模板已填写完整
- [ ] 临时处置已执行（如需要）
- [ ] 根因已定位并修复
- [ ] 服务恢复已验证（健康检查 + 业务功能）
- [ ] 复盘报告已输出并归档至知识库
- [ ] 改进措施已录入跟踪系统

## 注意事项
1. 排障过程中禁止执行未经审批的高风险操作（如删除 PVC、强制删除命名空间），须升级至运维负责人审批。
2. 日志收集须使用 `--tail` 限制行数，避免拉取过量日志导致 etcd 压力。
3. 政务云项目（湖南/西安/大连/烟台）排障需通过 VPN → 跳板机 → 虚机三级跳转，操作前须确认 VPN 连通性。
4. 多分支环境排障时须确认当前 Pod 运行的镜像 tag 与分支对应关系，避免误判版本问题。
5. Kafka/RabbitMQ 消息积压排查时，须先确认消费者 Pod 是否存活，再检查消息生产端异常。

## 工具与资源
- **kubectl**：Pod/Service/Deployment 状态查询与日志查看
- **Prometheus + Grafana**：`rate(http_requests_total{status="500"}[5m])` 等指标查询
- **Nacos 控制台**：namespace prod（daa3b24c-c5b0-4524-8322-58910e7bb739）配置查看与修改
- **Helm CLI**：`helm get values <release> -n msp-prod` 查看当前配置
- **XXL-JOB 控制台**：8013 端口，查看定时任务调度日志
- **journalctl**：`journalctl -u kubelet --since "1h ago"` 查看节点级日志
- **RAG 知识库**：历史故障复盘报告检索，辅助根因定位
