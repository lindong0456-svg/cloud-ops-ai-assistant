# K8s Pod Evicted 排障

## 故障现象

在 msp-prod 命名空间中，大量 Pod 状态变为 Evicted，节点上的 Pod 被驱逐后不会自动重新调度。执行 `kubectl get pods -n msp-prod` 显示多条 Evicted 记录，服务可用副本数下降。典型表现：

```
NAME                              READY   STATUS    RESTARTS   AGE
cspm-biz-7d4f5c6b8-x2k9m         0/1     Evicted   0          1h
cspm-biz-7d4f5c6b8-p3l1n         0/1     Evicted   0          1h
adapter-cloud-5f6g7h8i9-q4w7e    0/1     Evicted   0          1h
dispatch-gw-6a7b8c9d0-r5t6y      1/1     Running   0          2d
```

dispatch 网关（9091 端口）因后端 cspm 和 adapter 副本被驱逐，流量转发异常，用户请求返回 503。

## 影响评估

- **直接影响**：被驱逐的 Pod 不再运行，对应服务副本数减少。若驱逐波及多个节点，可能导致服务完全不可用。
- **连锁反应**：cspm 和 adapter 被驱逐后，dispatch 网关后端无可用 Pod，三层调用链断裂。200+ 监控指标采集中断，VictoriaMetrics 出现数据断点。
- **波及范围**：单节点驱逐影响该节点上的所有 Pod（可能涉及多个服务），多节点驱逐可能导致全局服务中断。POC7/POC15 等测试环境节点数少，驱逐影响更大。
- **残留问题**：Evicted Pod 不会自动清理，大量残留 Evicted 记录影响 `kubectl get pods` 输出可读性，且占用 etcd 存储。
- **业务等级**：P1 级故障，需 10 分钟内定位驱逐原因，15 分钟内恢复调度。

## 关联组件

| 组件 | 说明 |
|------|------|
| kubelet | 节点压力检测与驱逐执行者，根据阈值触发驱逐 |
| kube-scheduler | Pod 被驱逐后负责重新调度到其他健康节点 |
| Docker/containerd | 容器运行时，日志未轮转时磁盘被占满触发驱逐 |
| node-exporter | 上报节点磁盘、内存、PID 使用量到 VictoriaMetrics |
| VictoriaMetrics | 监控平台，可配置磁盘/内存使用率告警提前预警 |
| Ansible | 批量推送节点配置（如 docker daemon.json 日志轮转） |

## 常见原因

1. **节点磁盘压力**：节点磁盘使用率超过驱逐阈值（默认 85%），kubelet 触发 DiskPressure。最常见根因是容器日志未配置轮转，无限增长占满磁盘。
2. **节点内存压力**：可用内存不足，kubelet 触发 MemoryPressure 并按 QoS 等级（BestEffort > Burstable > Guaranteed）驱逐 Pod。
3. **PID 压力**：节点进程数过多（默认阈值 32768），触发 PIDPressure 驱逐。容器内僵尸进程或线程泄漏是常见诱因。
4. **日志未轮转**：容器日志文件（/var/lib/docker/containers/*/*.log）无限增长，是最常见的根因。adapter 服务采集 200+ 指标，日志量大，尤为突出。
5. **镜像层堆积**：旧镜像层未清理，/var/lib/docker 占用大量磁盘空间。

## 排障步骤

**步骤 1：确认被驱逐的 Pod 及所在节点**

```bash
kubectl get pods -n msp-prod | grep Evicted
```

期望输出：
```
cspm-biz-7d4f5c6b8-x2k9m         0/1     Evicted   0          1h
adapter-cloud-5f6g7h8i9-q4w7e    0/1     Evicted   0          1h
```

**步骤 2：查看驱逐原因**

```bash
kubectl describe pod <pod-name> -n msp-prod
```

重点查看 Status 中的 Reason 和 Message：

```
Status:       Failed
Reason:       Evicted
Message:      The node was low on resource: diskpressure. Node worker-03 is reporting pressure.
```

上例明确指出是磁盘压力导致驱逐，节点为 worker-03。

**步骤 3：检查节点状态**

```bash
kubectl describe node worker-03 | grep -A 10 Conditions
```

期望输出：
```
Conditions:
  Type             Status  LastHeartbeatTime
  ----             ------  -----------------
  MemoryPressure   False   Mon, 15 Jan 2024 14:30:00
  DiskPressure     True    Mon, 15 Jan 2024 14:30:00
  PIDPressure      False   Mon, 15 Jan 2024 14:30:00
  Ready            True    Mon, 15 Jan 2024 14:30:00
```

DiskPressure 为 True 即确认磁盘压力。

**步骤 4：登录节点排查磁盘**

```bash
# 政务云需通过跳板机 SSH 登录
ssh jumpuser@<bastion-ip>
ssh worker-03

# 查看磁盘分区使用率
df -h

# 定位大文件（容器日志）
du -sh /var/lib/docker/containers/* | sort -rh | head -10

# 查看镜像占用
docker system df
```

期望输出：
```
Filesystem      Size  Used Avail Use% Mounted on
/dev/vda1        50G   48G  2.0G  97% /var/lib/docker

18G  /var/lib/docker/containers/abc123/abc123-json.log
8G   /var/lib/docker/containers/def456/def456-json.log
```

**步骤 5：清理与恢复**

```bash
# 清理驱逐后的残留 Pod（让 Deployment 重新调度）
kubectl delete pod <pod-name> -n msp-prod

# 或批量清理所有 Evicted Pod
kubectl get pods -n msp-prod --field-selector status.phase=Failed -o json | \
  kubectl delete -f -

# 节点上清理大日志文件
truncate -s 0 /var/lib/docker/containers/*/*-json.log

# 清理无用镜像
docker image prune -a --filter "until=168h"
```

**步骤 6：修复日志轮转配置**

```bash
# 编辑 docker daemon.json
cat > /etc/docker/daemon.json << 'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m",
    "max-file": "5"
  }
}
EOF

# 重启 docker
systemctl restart docker
```

## 监控指标

```promql
# 1. 节点文件系统可用空间（字节）
node_filesystem_avail_bytes{mountpoint="/var/lib/docker"}

# 2. 磁盘使用率（超过 80% 预警，85% 触发驱逐）
1 - (node_filesystem_avail_bytes{mountpoint="/var/lib/docker"} / node_filesystem_size_bytes{mountpoint="/var/lib/docker"})

# 3. Evicted 状态的 Pod 数量
count(kube_pod_status_phase{phase="Failed", namespace="msp-prod"} == 1)

# 4. 节点压力状态（DiskPressure/MemoryPressure/PIDPressure）
kube_node_status_condition{condition="DiskPressure", status="true"}
kube_node_status_condition{condition="MemoryPressure", status="true"}

# 5. 节点可用内存
node_memory_MemAvailable_bytes

# 6. 容器日志文件大小（需 node-exporter textfile collector）
node_textfile_docker_log_size_bytes
```

在 VictoriaMetrics 中配置告警：磁盘使用率 > 80% 预警、> 85% 紧急告警；节点 DiskPressure 为 true 立即告警。

## 真实案例

**环境**：POC7 测试环境，msp-prod 命名空间
**时间线**：
- 10:00 — VictoriaMetrics 告警：worker-03 节点磁盘使用率 92%
- 10:05 — `kubectl get pods -n msp-prod` 发现 cspm 和 adapter 共 15 个 Pod 突然被 Evicted
- 10:07 — `kubectl describe node worker-03` 显示 DiskPressure 为 True
- 10:10 — SSH 登录 worker-03，`df -h` 发现 `/var/lib/docker` 分区使用率 97%
- 10:12 — `du -sh /var/lib/docker/containers/*` 定位到单个 adapter Pod 日志文件达 18GB
- 10:15 — 确认根因：Docker daemon.json 中未配置 log-opts 的 max-size 和 max-file 参数，容器日志无限制增长

**根因分析**：POC7 环境的 adapter 服务采集 200+ 监控指标，日志输出量大（每分钟约 5MB）。节点未配置容器日志轮转，日志文件持续增长至 18GB，触发磁盘驱逐阈值（85%）。kubelet 按 QoS 等级驱逐了节点上所有 BestEffort 和部分 Burstable Pod。

**处置步骤**：
1. 在 worker-03 上配置 `/etc/docker/daemon.json`，设置 `max-size: 100m`、`max-file: 5`
2. 执行 `systemctl restart docker` 重启 Docker（注意：会短暂中断该节点上的容器）
3. 手动清理旧日志：`truncate -s 0 /var/lib/docker/containers/*/*-json.log`
4. 清理无用镜像：`docker image prune -a --filter "until=168h"`
5. 清理 Evicted Pod：`kubectl delete pod -n msp-prod --field-selector status.phase=Failed`
6. 确认 Pod 重新调度：`kubectl get pods -n msp-prod | grep -E 'cspm|adapter'`，全部 Running
7. 通过 Ansible 批量推送 daemon.json 到所有节点（POC2/POC4/POC7/POC15 及政务云节点）

## 处置建议

- 所有节点统一配置容器日志轮转，通过 Ansible 批量推送 daemon.json，设置 max-size 为 100m、max-file 为 5。
- VictoriaMetrics 中设置磁盘使用率超 80% 告警，提前预警避免被动驱逐。
- Evicted Pod 不会自动清理，建议配置定时清理 CronJob 或设置 PodGarbageCollection 阈值。
- 政务云环境磁盘规格较小，cspm 服务日志量大，建议同时配置应用级日志轮转（如 Logback 的 RollingFileAppender）。

## 预防措施

1. **统一日志轮转配置**：通过 Ansible playbook 批量推送 docker daemon.json 到所有节点，确保 max-size=100m、max-file=5。新节点初始化时自动包含此配置。
2. **磁盘容量告警**：VictoriaMetrics 配置三级告警——70% 提示、80% 预警、85% 紧急，确保在驱逐触发前介入处理。
3. **定期镜像清理**：通过 Ansible 定时执行 `docker image prune -a --filter "until=168h"` 清理 7 天前未使用的镜像层。
4. **Evicted Pod 自动清理**：部署 CronJob 每小时执行 `kubectl delete pod --field-selector status.phase=Failed` 清理残留 Pod。
5. **应用级日志控制**：cspm 和 adapter 服务的 Logback 配置中设置 RollingFileAppender，maxFileSize=50MB、maxIndex=10。adapter 采集 200+ 指标的 DEBUG 日志仅在排障时开启。
6. **磁盘容量规划**：政务云节点磁盘不低于 100GB，/var/lib/docker 独立分区，预留 30% 冗余空间。

## 相关命令速查

```bash
# 查看 Evicted Pod
kubectl get pods -n msp-prod | grep Evicted
kubectl describe pod <pod-name> -n msp-prod

# 批量清理 Evicted Pod
kubectl get pods -n msp-prod --field-selector status.phase=Failed -o json | kubectl delete -f -
kubectl delete pod -n msp-prod --field-selector status.phase=Failed

# 检查节点状态
kubectl describe node <node-name> | grep -A 10 Conditions
kubectl get nodes -o wide

# 节点磁盘排查（SSH 登录后）
df -h
du -sh /var/lib/docker/containers/* | sort -rh | head -10
docker system df

# 清理磁盘
truncate -s 0 /var/lib/docker/containers/*/*-json.log
docker image prune -a --filter "until=168h"
docker system prune -a --volumes

# 配置日志轮转
cat > /etc/docker/daemon.json << 'EOF'
{"log-driver":"json-file","log-opts":{"max-size":"100m","max-file":"5"}}
EOF
systemctl restart docker

# Ansible 批量推送配置
ansible all -m copy -a "src=daemon.json dest=/etc/docker/daemon.json" -b
ansible all -m systemd -a "name=docker state=restarted" -b
```
