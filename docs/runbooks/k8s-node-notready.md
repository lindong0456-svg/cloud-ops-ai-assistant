# K8s 节点 NotReady 排障

## 故障现象

在 msp-prod 所在集群中，`kubectl get nodes` 显示某节点 STATUS 为 NotReady，该节点上所有 Pod 状态标记为 Unknown。调度器不再向该节点分配新 Pod，已有服务受到影响。典型表现：

```
NAME           STATUS     ROLES    AGE    VERSION
master-01      Ready      master   120d   v1.26.3
worker-03      NotReady   <none>   89d    v1.26.3
worker-04      Ready      <none>   89d    v1.26.3
```

msp-prod 中部署在 worker-03 上的 cspm 或 adapter 副本不可达，dispatch 网关（9091 端口）健康检查失败，请求超时或返回 503。

## 影响评估

- **直接影响**：NotReady 节点上的 Pod 进入 Unknown 状态，既不确认运行也不确认终止，服务流量无法正常路由。节点上所有 Pod 的 readinessProbe 失效，endpoint 控制器将其从 Service 的 endpoints 中摘除。
- **连锁反应**：若 NotReady 节点上运行了 cspm 或 adapter 的部分副本，可用副本数下降。当剩余副本不足以承载流量时，dispatch 网关返回 503，三层调用链中断。
- **波及范围**：影响该节点上所有命名空间的 Pod。若节点上运行了 VictoriaMetrics 的 vmselect 或 vmstorage 组件，监控数据查询和存储也会受影响。
- **数据风险**：节点上 Pod 的本地状态未知，强制驱逐可能导致数据不一致（特别是有状态服务）。
- **业务等级**：P1 级故障，需 5 分钟内确认节点状态，15 分钟内恢复或完成 Pod 迁移。

## 关联组件

| 组件 | 说明 |
|------|------|
| kubelet | 节点代理，周期性向 API Server 上报心跳，NotReady 的直接原因是心跳中断 |
| kube-apiserver | 接收 kubelet 心跳，更新 Node 资源状态 |
| containerd/docker | 容器运行时，kubelet 依赖其执行 Pod 生命周期管理 |
| kube-scheduler | 节点 NotReady 后停止向该节点调度新 Pod |
| node-exporter | 上报节点指标到 VictoriaMetrics，节点 NotReady 后指标中断 |
| crictl | 容器运行时 CLI 工具，用于排查 CRI 接口问题 |
| Ansible | 节点组件安装与版本管理 |

## 常见原因

1. **kubelet 异常**：kubelet 进程崩溃、停止或内存泄漏，无法上报节点状态。心跳超时默认 40 秒后节点标记为 NotReady。
2. **网络不通**：节点与 API Server 的网络中断（防火墙规则变更、VPN 断连、网卡故障），心跳上报失败。
3. **容器运行时故障**：containerd 或 docker 进程异常、CRI 接口不兼容，kubelet 无法执行 Pod 生命周期管理。政务云 ARM 节点尤为常见。
4. **节点资源耗尽**：内存耗尽导致 kubelet 自身被 OOM Killer 杀死，或 PID 耗尽导致无法创建新进程。
5. **证书过期**：kubelet 客户端证书过期，与 API Server 的 TLS 握手失败。政务云项目部署超过 1 年后需关注。
6. **内核 panic**：节点内核崩溃或硬件故障（磁盘损坏、内存条故障）。

## 排障步骤

**步骤 1：确认节点状态与心跳时间**

```bash
kubectl describe node <node-name>
```

重点查看 Conditions 区域：

```
Conditions:
  Type             Status  LastHeartbeatTime       LastTransitionTime      Reason
  ----             ------  -----------------       ------------------      ------
  MemoryPressure   Unknown Mon, 15 Jan 2024 14:00  Mon, 15 Jan 2024 14:05  NodeStatusUnknown
  DiskPressure     Unknown Mon, 15 Jan 2024 14:00  Mon, 15 Jan 2024 14:05  NodeStatusUnknown
  Ready            False   Mon, 15 Jan 2024 14:00  Mon, 15 Jan 2024 14:05  KubeletNotReady
```

LastHeartbeatTime 距当前时间超过 40 秒说明心跳中断。Conditions 全部 Unknown 说明 kubelet 完全失联。

**步骤 2：SSH 登录节点检查 kubelet**

```bash
# 政务云需通过跳板机
ssh jumpuser@<bastion-ip>
ssh worker-03

# 检查 kubelet 服务状态
systemctl status kubelet
```

期望输出（异常情况）：
```
kubelet.service - kubelet
   Active: inactive (dead) since Mon 2024-01-15 14:03:00
```

若 kubelet 已停止，执行 `systemctl start kubelet` 并查看日志。

**步骤 3：查看 kubelet 日志**

```bash
journalctl -u kubelet --since "30 min ago" --no-pager | tail -100
```

期望输出（CRI 不兼容示例）：
```
Jan 15 14:03:15 worker-03 kubelet[12345]: E0115 14:03:15.123456 12345 kubelet.go:1170] Image garbage collection failed
Jan 15 14:03:15 worker-03 kubelet[12345]: E0115 14:03:15.123789 12345 kuberuntime_manager.go:721] Failed to get container info: CRI v1 runtime is not implemented for endpoint "unix:///run/containerd/containerd.sock"
```

**步骤 4：验证容器运行时**

```bash
# 检查 containerd 状态
systemctl status containerd

# 使用 crictl 验证 CRI 接口
crictl ps
crictl info
```

期望输出（正常）：
```
CONTAINER           IMAGE               CREATED             STATE               NAME
abc123              cspm:1.2.3          2 hours ago         Running             cspm-biz

{"status": {"conditions": [{"type": "RuntimeReady", "status": true}, {"type": "NetworkReady", "status": true}]}}
```

若 crictl 报版本不兼容错误，需检查 crictl 与 containerd 版本匹配。

**步骤 5：检查网络连通性**

```bash
# 测试与 API Server 的连通性
curl -k https://<api-server-ip>:6443/healthz

# 检查防火墙规则
iptables -L INPUT -n --line-numbers | grep 6443

# 检查证书有效期
openssl x509 -in /var/lib/kubelet/pki/kubelet-client-current.pem -noout -dates
```

期望输出（证书正常）：
```
notBefore=Jan 15 00:00:00 2023 GMT
notAfter=Jan 15 00:00:00 2024 GMT
```

若 notAfter 已过期，需通过 `kubeadm cert renew` 续期。

## 监控指标

```promql
# 1. 节点 Ready 状态（0=NotReady, 1=Ready）
kube_node_status_condition{condition="Ready", status="true"}

# 2. 节点心跳时间间隔（超过 40 秒需告警）
time() - kube_node_heartbeat_margin_time

# 3. kubelet 运行状态
up{job="node-exporter"}

# 4. 节点上 Pod 状态分布
count by (phase) (kube_pod_status_phase)

# 5. containerd 运行状态
up{job="cadvisor"}

# 6. 节点 CPU 负载（辅助判断资源耗尽）
node_load1
node_load5
```

在 VictoriaMetrics 中配置告警：`kube_node_status_condition{condition="Ready",status="true"} == 0` 持续 1 分钟触发 P1 告警。

## 真实案例

**环境**：西安政务云项目，ARM 架构集群
**时间线**：
- 08:00 — VictoriaMetrics 告警：worker-arm-03 节点 NotReady
- 08:02 — `kubectl get nodes` 确认 worker-arm-03 STATUS 为 NotReady，LastHeartbeatTime 停在 07:55
- 08:05 — 通过跳板机 SSH 登录 worker-arm-03，`systemctl status kubelet` 显示 active (running)
- 08:07 — `journalctl -u kubelet --since "15 min ago" | tail -50` 发现报错：`failed to get container info: CRI v1 runtime is not implemented for endpoint "unix:///run/containerd/containerd.sock"`
- 08:10 — 执行 `crictl ps` 报版本不兼容错误
- 08:12 — 检查版本：`crictl --version` 为 1.25.0，`containerd --version` 为 1.6.10
- 08:15 — 确认根因：crictl 1.25 与 containerd 1.6 的 CRI v1 接口在 ARM64 平台存在兼容性问题

**根因分析**：西安政务云部署时，ARM 节点的 crictl 二进制文件从 x86 集群的配置模板复制而来，版本为 1.25.0。而该节点的 containerd 为 1.6.10 ARM64 版本。crictl 1.25 默认使用 CRI v1 接口，但 containerd 1.6 在 ARM64 平台上 CRI v1 实现存在缺陷，导致 kubelet 无法获取容器信息，最终节点标记为 NotReady。

**处置步骤**：
1. 下载与 containerd 1.6 兼容的 crictl 1.6 ARM64 版本：
   `wget https://github.com/kubernetes-sigs/cri-tools/releases/download/v1.6.10/crictl-v1.6.10-linux-arm64.tar.gz`
2. 替换 `/usr/bin/crictl`：`tar xzf crictl-v1.6.10-linux-arm64.tar.gz -C /usr/bin/`
3. 重启 kubelet：`systemctl restart kubelet`
4. 验证：`kubectl get nodes`，worker-arm-03 恢复 Ready
5. 验证 Pod：`kubectl get pods -n msp-prod -o wide | grep worker-arm-03`，所有 Pod 恢复 Running
6. 在 Ansible playbook 中增加 crictl 与 containerd 版本一致性校验，部署前自动检测

## 处置建议

- 通过 Ansible 统一管理节点组件版本，部署前校验 kubelet、crictl、containerd 三者版本兼容性矩阵。
- 政务云 ARM 节点需特别关注二进制架构匹配，建议维护一张版本兼容表，记录各环境实际使用的版本组合。
- 设置 VictoriaMetrics 告警：节点 NotReady 超过 1 分钟即触发告警，通知值班人员。
- 跳板机访问凭据应纳入统一管理，确保排障时可快速登录目标节点，避免 VPN 拨号延迟影响响应速度。

## 预防措施

1. **版本兼容性矩阵**：维护 kubelet、crictl、containerd 版本兼容表，Ansible playbook 中增加部署前自动校验逻辑，版本不匹配时拒绝部署。
2. **证书自动续期**：配置 kubelet 证书自动轮转（feature gate RotateKubeletClientCertificate），通过 `kubeadm cert check-expiration` 定期巡检，政务云项目超过 10 个月的证书需提前续期。
3. **心跳监控告警**：VictoriaMetrics 配置节点心跳间隔 > 40 秒的预警和 NotReady 的 P1 告警，确保 1 分钟内发现节点异常。
4. **节点冗余**：每个政务云集群至少保持 2 个 worker 节点，单个节点 NotReady 时 Pod 可迁移至其他节点。POC 环境建议使用 kind/minikube 多节点模拟。
5. **定期健康巡检**：通过 Ansible 定时执行 `systemctl status kubelet/containerd` 和 `crictl ps` 巡检，结果汇总到 VictoriaMetrics。
6. **应急预案**：对于无法快速恢复的节点，执行 `kubectl drain <node> --ignore-daemonsets --force` 将 Pod 迁移至健康节点，确保服务可用性。

## 相关命令速查

```bash
# 查看节点状态
kubectl get nodes -o wide
kubectl describe node <node-name>
kubectl describe node <node-name> | grep -A 10 Conditions

# SSH 登录（政务云通过跳板机）
ssh jumpuser@<bastion-ip> && ssh <node-ip>

# kubelet 相关
systemctl status kubelet
systemctl restart kubelet
journalctl -u kubelet --since "30 min ago" --no-pager | tail -100

# containerd 相关
systemctl status containerd
systemctl restart containerd
crictl ps
crictl info
crictl logs <container-id>

# 网络与证书
curl -k https://<api-server>:6443/healthz
openssl x509 -in /var/lib/kubelet/pki/kubelet-client-current.pem -noout -dates
kubeadm certs check-expiration

# 驱逐 Pod 迁移
kubectl drain <node-name> --ignore-daemonsets --force
kubectl uncordon <node-name>

# 查看节点上的 Pod
kubectl get pods -n msp-prod -o wide --field-selector spec.nodeName=<node-name>

# 版本检查
kubelet --version
crictl --version
containerd --version
```
