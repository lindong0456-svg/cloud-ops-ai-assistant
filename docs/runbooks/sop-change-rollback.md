# 变更回滚 SOP

## 概述
本文档规范联通数科云管平台（MSP）变更回滚操作流程，覆盖变更前准备、回滚执行、回滚验证及多环境同步策略。平台采用 Helm 管理部署，多 POC 环境各有独立镜像仓库，回滚需兼顾 Helm revision 与镜像版本双重管理。CI/CD 流水线为：Nginx gateway 配置 → Helm values.yaml → helm upgrade → Docker build/load/tag/push → K8s Pod 重启。Ansible 用于多节点镜像管理。

## 角色与职责

| 角色 | 职责 |
|------|------|
| 变更执行人 | 执行变更与回滚操作，变更前完成备份与记录 |
| 运维负责人 | 审批生产环境变更与回滚，P0 回滚须到场指挥 |
| 模块负责人 | 确认回滚方案的技术可行性，评估回滚对依赖模块的影响 |
| CI/CD 工程师 | 维护流水线预校验机制，优化回滚自动化 |
| SRE 工程师 | 监控回滚过程中的指标变化，确认服务恢复 |

## 变更前准备

每次变更前必须完成以下准备工作，否则不得执行变更：

### 备份流程
```bash
# 1. 备份当前 Helm 配置
helm get values <release> -n msp-prod > values-backup-$(date +%Y%m%d).yaml

# 2. 记录当前镜像版本（如 7.7.0-patch23-20251029_cloud）
kubectl -n msp-prod get pods -o jsonpath='{.items[*].spec.containers[*].image}' | tr ' ' '\n' | sort -u

# 3. 查看当前 Helm revision
helm history <release> -n msp-prod
helm list -n msp-prod

# 4. 记录 Nginx gateway 当前配置
kubectl -n msp-prod get configmap nginx-gateway-config -o yaml > nginx-backup-$(date +%Y%m%d).yaml
```

### 变更前检查流程
备份 values.yaml → 记录镜像 tag → 记录 Helm revision → 备份 Nginx 配置 → 确认回滚命令可用 → 通知相关模块负责人

## 回滚操作

### Helm 回滚（首选方案）
```bash
# 查看历史版本，确认目标 revision
helm history <release> -n msp-prod
# 回滚至指定 revision
helm rollback <release> <revision> -n msp-prod
# 确认回滚状态
helm list -n msp-prod
```

### K8s Deployment 回滚
```bash
# 查看滚动更新历史
kubectl -n msp-prod rollout history deployment/<deploy-name>
# 回滚至上一版本
kubectl -n msp-prod rollout undo deployment/<deploy-name>
# 回滚至指定版本
kubectl -n msp-prod rollout undo deployment/<deploy-name> --to-revision=<n>
```

### 镜像回退（Helm revision 不可用时）
```bash
# 重新 tag 旧版本镜像
docker tag <registry>/<image>:<旧tag> <registry>/<image>:<目标tag>
# push 至对应 POC registry
docker push <registry>/<image>:<目标tag>
# 触发 Pod 重新拉取镜像
kubectl -n msp-prod rollout restart deployment/<deploy>
```

### Ansible 批量镜像回退
```bash
# 多节点批量回退镜像
ansible-playbook -i inventory/prod playbook/rollback-images.yml \
  -e "image_tag=7.7.0-patch23-20251029_cloud" \
  -e "target_registry=172.25.1.250:5000"
```

## 回滚验证

### 验证流程
执行 rollout status → 检查 Pod 状态 → 健康检查 → 监控指标确认 → 业务功能验证

```bash
# 1. 确认滚动更新完成
kubectl -n msp-prod rollout status deployment/<deploy>
# 2. 检查 Pod 状态全部 Running
kubectl -n msp-prod get pods -l app=<app> -o wide
# 3. 健康检查接口返回 200
kubectl -n msp-prod exec <pod> -- curl -s -o /dev/null -w "%{http_code}" http://localhost:<port>/actuator/health
# 4. 检查 Nginx upstream 配置是否正确
kubectl -n msp-prod exec <nginx-pod> -- nginx -T | grep -A5 upstream
```
确认 Prometheus 查询无异常告警，业务功能验证关键链路走通（dispatch → cspm → adapter 全链路）。

## 多环境回滚策略

### 策略流程
POC 环境先行验证 → 多 Registry 同步回退 → 分支对应确认 → Ansible 批量执行

- **POC 先行**：生产回滚前先在对应 POC 环境验证回滚方案可行。POC2（172.25.1.250）/ POC4（172.25.4.250）/ POC7（172.25.5.222）/ POC15（172.25.35.250）
- **多 Registry 同步**：各 POC 环境独立 registry 需同步回退镜像，使用 Ansible playbook 批量执行
- **分支对应**：回滚镜像 tag 须与对应分支匹配，如 `7.7.0-patch23-20251029_cloud`、`7.0.4.2-it-20241022-patch22-20251226`、`7.0.4.4-xianduoyun-20250416`
- **Ansible 批量**：多节点镜像回退统一使用 Ansible playbook，避免手动操作遗漏

## 真实案例

**时间**：2025 年 10 月 29 日
**事件**：联通 MSP 平台执行 `7.7.0-patch23-20251029_cloud` 分支 Helm upgrade 后，dispatch 网关 Nginx 配置异常导致平台部分接口 502。

**时间线**：
- 14:00 — 变更窗口开始，执行 `helm upgrade dispatch <chart> -n msp-prod -f values-7.7.0-patch23.yaml`
- 14:02 — Helm upgrade 完成，Pod 滚动更新启动
- 14:05 — Prometheus 告警：dispatch 网关 HTTP 502 速率 >30%
- 14:06 — 值班人员确认故障，判定 P0 级别
- 14:07 — 查阅变更前备份：`helm history dispatch -n msp-prod` 确认 revision 14 为上一稳定版本
- 14:08 — 执行 `helm rollback dispatch 14 -n msp-prod`
- 14:09 — Pod 滚动回滚完成，502 告警消除
- 14:10 — 全链路功能验证通过，平台恢复正常
- 14:30 — 确认稳定，解除应急状态
- 次日 — 复盘报告完成

**根因分析**：values.yaml 中 Nginx upstream 配置 YAML 缩进错误，导致 upstream 后端地址解析异常。

**经验教训**：
1. CI/CD 流水线须在 `helm upgrade` 前增加 `helm template` 预校验步骤，检查 YAML 语法
2. Nginx 配置变更须增加 `nginx -t` 预校验，在 Pod 启动前拦截配置错误
3. 变更前备份机制有效，从告警到回滚完成仅 3 分钟
4. 多分支管理时，values.yaml 文件须按分支命名（如 `values-7.7.0-patch23.yaml`），避免误用

## 检查清单
- [ ] 变更前已备份 values.yaml（`helm get values`）
- [ ] 已记录当前镜像版本与 Helm revision 号
- [ ] 已备份 Nginx gateway 配置
- [ ] 回滚命令已提前确认并验证可用
- [ ] 回滚操作已执行并确认无报错
- [ ] `rollout status` 确认滚动更新完成
- [ ] Pod 状态全部 Running 且无重启
- [ ] 健康检查接口返回 200
- [ ] Prometheus 监控指标正常无告警
- [ ] 多环境同步回退完成（如需要，使用 Ansible 批量执行）
- [ ] 回滚后分支与镜像 tag 对应关系已确认

## 注意事项
1. Helm rollback 不会回退 ConfigMap/PVC 等有状态资源，须手动检查这些资源是否需要回退。
2. 镜像回退时须确认目标 POC registry 中存在旧版本镜像，避免 pull 失败。
3. 回滚后须检查 Nacos 中的配置是否与回滚版本兼容，特别是 dispatch/cspm/adapter 的连接参数。
4. 多环境回滚须遵循 POC 先行原则，禁止直接在生产环境执行未经验证的回滚方案。
5. 政务云项目回滚须通过 VPN → 跳板机操作，预留额外网络延迟时间，回滚窗口须加长 30 分钟。
6. 回滚操作完成后须保留变更前备份文件至少 7 天，以备后续排查需要。
7. 分支管理须严格对应，如 `7.0.4.4-xianduoyun-20250416` 分支的回滚不得使用 `7.7.0-patch23` 的 values.yaml。

## 工具与资源
- **Helm CLI**：`helm get values`/`helm history`/`helm rollback` 回滚核心工具
- **kubectl**：`rollout undo`/`rollout status` Deployment 级回滚
- **Docker CLI**：`docker tag`/`docker push` 镜像回退
- **Ansible**：多节点批量镜像回退 playbook（inventory/prod）
- **Nacos 控制台**：namespace prod（daa3b24c-c5b0-4524-8322-58910e7bb739）配置兼容性检查
- **Prometheus + Grafana**：回滚后监控指标验证
- **POC Registry**：POC2（172.25.1.250:5000）/ POC4（172.25.4.250:5000）/ POC7（172.25.5.222:5000）/ POC15（172.25.35.250:5000）
- **CI/CD 流水线**：`helm template` 预校验 + `nginx -t` 配置校验
