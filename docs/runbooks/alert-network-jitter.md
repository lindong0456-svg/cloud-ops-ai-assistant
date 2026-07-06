# 网络抖动告警处理

## 告警规则

告警名称：`NetworkJitterDetected`

PromQL 表达式：

```promql
# 接收丢包率（核心告警）
rate(node_network_receive_drop_total{device=~"eth0|ens.*"}[5m]) / rate(node_network_receive_packets_total{device=~"eth0|ens.*"}[5m]) > 0.01

# 发送丢包率
rate(node_network_transmit_drop_total{device=~"eth0|ens.*"}[5m]) / rate(node_network_transmit_packets_total{device=~"eth0|ens.*"}[5m]) > 0.01

# 延迟 P99（辅助告警）
histogram_quantile(0.99, sum(rate(request_duration_seconds_bucket[5m])) by (le, service)) > 0.1
```

指标说明：`node_network_receive_drop_total` 是 Node Exporter 采集的网卡接收丢包累计数，除以 `node_network_receive_packets_total`（接收总包数）得到丢包率。`device` 标签过滤物理网卡（eth0/ens*），排除 lo 回环接口。`histogram_quantile(0.99, ...)` 计算请求延迟 P99 分位数，`request_duration_seconds_bucket` 是应用层延迟直方图，`le` 标签定义桶上限，`service` 标签区分服务。

触发条件：丢包率超过 1% 或网络延迟 P99 超过 100ms。该告警覆盖 10 类产品（vm/phy/bms/ECS/aicp-notebook/aicp-job/aicp-isvc/netDevice/aicc/eip）的网络指标，包括带宽使用率、丢包率、错包率等。数据通过 dispatch（9091）→ cspm → adapter 采集写入 VM，XXL-JOB 每 3 小时同步至 MongoDB 生成网络质量报表。

## 告警分级

| 级别 | 阈值条件 | 响应时效 | 通知方式 |
|------|---------|---------|---------|
| P0 | 丢包率 > 5% 或 P99 > 500ms，业务全线超时 | 5 分钟内 | 电话 + 钉钉 + 短信 |
| P1 | 丢包率 > 1% 或 P99 > 100ms 持续 5min | 15 分钟内 | 电话 + 钉钉告警卡片 |
| P2 | 丢包率 > 0.5% 或 P99 > 50ms | 30 分钟内 | 钉钉群机器人推送 |
| P3 | 错包率 > 0.1% 或带宽使用率 > 80%（预警） | 巡检处理 | 钉钉群消息 |

## 影响评估

网络抖动直接影响业务可用性：服务间调用超时导致请求失败率上升、跨可用区数据同步延迟增大、GPU 训练任务 checkpoint 传输失败、用户请求响应时间劣化。若发生在 dispatch（9091）与 VM 之间的采集链路，将导致指标采集延迟和丢数据；若发生在 AICP 算力节点间，分布式训练任务可能因通信超时而中断。

## 关联组件

- **Calico/Cilium**：CNI 网络插件，负责 Pod 间网络路由，策略异常可能导致丢包
- **kube-proxy**：Service 负载均衡，iptables/ipvs 规则异常影响转发
- **Istio/Envoy**：Service Mesh 代理层，Sidecar 注入增加网络跳数和延迟
- **netDevice**：网络设备指标采集，交换机/路由器丢包错包监控
- **eip**：弹性公网 IP，带宽超限导致外网访问丢包
- **dispatch（9091）→ cspm → adapter**：采集链路，链路中任一节点网络异常影响全平台指标
- **VictoriaMetrics**：远程写入依赖网络，抖动导致指标写入延迟

## 排障步骤

1. **查网络指标时序**：通过 VM `/v2/commonQuery`（MongoDB 风格查询转 PromQL）查询丢包率、错包率、带宽使用率近 1 小时时序数据，定位抖动起始时间。PromQL：`rate(node_network_receive_drop_total[5m]) / rate(node_network_receive_packets_total[5m])`。通过 `/v2/oneAgg` 聚合查询 10 类产品（vm/phy/bms/ECS 等）的网络指标，确认影响范围。
2. **ping/traceroute 定位抖动节点**：从告警节点 ping 目标节点，执行 `traceroute <target-ip>` 逐跳定位丢包位置。若所有跳数正常但端到端丢包，排查应用层；若某一跳开始丢包，定位网络设备问题。
3. **检查 MTU 配置**：执行 `ip link show` 查看告警节点 MTU，对比集群内其他节点。跨可用区通信 MTU 不一致是最常见丢包根因。通过 `/v1/query/standard` 查询所有节点 MTU 基线。
4. **检查网卡与内核参数**：执行 `ethtool -S eth0` 查看网卡硬件丢包计数，`sysctl net.core.rmem_max net.core.wmem_max` 检查内核缓冲区大小，`sysctl net.ipv4.tcp_.*` 检查 TCP 参数。
5. **检查 CNI/Service Mesh**：查看 Calico 节点状态 `calicoctl node status`，检查 Istio `istioctl analyze` 输出。确认是否有策略变更或配置推送异常。
6. **排查跨可用区流量**：通过 `/v2/oneAgg` 查询跨 AZ Pod 通信流量，评估是否需要调整 Pod 调度策略。检查 Pod 亲和性配置，同业务 Pod 是否分散在不同 AZ 导致大量跨区通信。
7. **查网络设备指标**：通过 netDevice 产品线查询交换机端口丢包、错包、带宽使用率。PromQL：`rate(netdevice_port_in_discards[5m])`。确认是否为物理网络设备故障。

## 关键 PromQL

```promql
# 1. 接收丢包率——核心指标
# node_network_receive_drop_total：网卡接收丢包累计数
# node_network_receive_packets_total：网卡接收总包数
rate(node_network_receive_drop_total{instance="$node"}[5m]) / rate(node_network_receive_packets_total{instance="$node"}[5m])

# 2. 发送丢包率——排查发送方向问题
rate(node_network_transmit_drop_total{instance="$node"}[5m]) / rate(node_network_transmit_packets_total{instance="$node"}[5m])

# 3. 错包率——排查物理层/驱动问题
# node_network_receive_errs_total：接收错误包累计数
rate(node_network_receive_errs_total{instance="$node"}[5m]) / rate(node_network_receive_packets_total{instance="$node"}[5m])

# 4. 带宽使用率——排查带宽瓶颈
# node_network_receive_bytes_total：接收字节数，x8 转比特
rate(node_network_receive_bytes_total{instance="$node"}[5m]) * 8 / on(instance) node_network_speed_bytes

# 5. 网络 P99 延迟——应用层抖动感知
# histogram_quantile 计算 99 分位延迟
histogram_quantile(0.99, sum(rate(request_duration_seconds_bucket{service="$svc"}[5m])) by (le))

# 6. 网卡硬件丢包——排查 ring buffer 溢出
# node_network_receive_fifo_total：FIFO 缓冲区溢出丢包
rate(node_network_receive_fifo_total{instance="$node"}[5m])

# 7. TCP 重传率——排查传输可靠性
rate(node_netstat_Tcp_RetransSegs[5m]) / rate(node_netstat_Tcp_OutSegs[5m])
```

## 真实案例

**时间线**：2024 年 6 月，联通数科 MSP 生产环境，跨可用区 Pod 通信异常。

**T+0min**：网络抖动告警触发，丢包率达 3%，P1 告警。部分服务间歇性超时，用户反馈请求偶发 502。

**T+5min**：值班人员通过 VM `/v2/oneAgg` 查询 10 类产品（vm/phy/bms/ECS/aicp-notebook/aicp-job/aicp-isvc/netDevice/aicc/eip）网络指标时序，定位丢包发生在可用区 A 与可用区 B 之间的通信链路。

**T+10min**：从 A 区节点 ping B 区节点，丢包率 3%；`traceroute` 显示经过 3 跳后开始丢包，丢包发生在跨 AZ 链路。

**T+15min**：执行 `ip link show` 对比两区节点 MTU 配置：可用区 A 节点 MTU 为 1500，可用区 B 节点 MTU 为 1450。大包从 A 区发往 B 区时超过 B 区 MTU 限制，导致分片失败丢包。

**T+20min**：通过 `/v2/commonQuery` 查询错包率指标同步偏高（0.8%），确认与 MTU 不匹配相关。查询 netDevice 网络设备指标，带宽使用率正常（45%），排除带宽瓶颈因素。查询 eip 弹性公网 IP 带宽，外网访问正常。

**T+25min**：进一步排查发现 A 区 Kubernetes 节点使用默认 MTU 1500（装机未定制），B 区节点经过 Calico IPIP 隧道封装后 MTU 被设置为 1450。跨区 Pod 直连时 MTU 协商失败。

**根因**：可用区 A 与可用区 B 节点 MTU 配置不一致（1500 vs 1450）。大包从 A 区发往 B 区时超过 B 区 MTU 限制，IP 分片在 IPIP 隧道中失败导致丢包。错包率偏高也由分片异常引起。带宽使用率正常，排除带宽瓶颈。

**处置**：（1）统一所有节点 MTU 配置为 1450（取较小值确保跨可用区一致），通过 Ansible 批量执行 `ip link set eth0 mtu 1450`；（2）Pod 间通信强制走 Service（ClusterIP）而非直连 Pod IP，由 kube-proxy 统一处理路由；（3）配置 Pod 亲和性策略，同业务 Pod 优先调度至同一可用区。丢包率于 T+45min 降至 0.05%。

## 自动分诊流程

AI Agent 接收 `NetworkJitterDetected` 告警后执行以下自动化分诊：

1. **告警解析**：提取 `instance`、`device` 标签，判定告警节点、网卡和级别。
2. **多维指标采集**：通过 VM `/v2/oneAgg` 并行查询丢包率、错包率、带宽使用率、TCP 重传率、P99 延迟，构建网络健康度视图。
3. **影响范围判定**：通过 `/v2/commonQuery` 聚合查询 10 类产品网络指标，确认是单节点、跨 AZ 还是全集群抖动。
4. **MTU 一致性检查**：自动对比告警节点与同集群其他节点的 MTU 配置，识别不一致节点。
5. **链路定位**：自动执行 ping/traceroute，逐跳分析丢包位置，区分节点本地问题与跨节点链路问题。
6. **组件健康检查**：检查 Calico 节点状态、kube-proxy iptables 规则、Istio 配置一致性，排除 CNI/Service Mesh 异常。
7. **根因匹配**：基于规则引擎匹配常见模式——MTU 不一致（跨 AZ 丢包 + 错包率高）、带宽超限（带宽使用率 > 80%）、网卡故障（硬件丢包计数高）、Calico 策略异常（单 Pod 丢包）。
8. **处置建议**：生成处置卡片推送钉钉群，包含 MTU 统一命令、Pod 亲和性配置、Ansible 批量脚本。

## 处置建议

1. 统一所有节点 MTU 配置为 1450（取较小值确保跨可用区一致），通过 Ansible 批量执行。
2. Pod 间通信强制走 Service（ClusterIP）而非直连 Pod IP，由 kube-proxy 统一处理路由。
3. 网络抖动告警纳入 200+ 指标巡检范围，XXL-JOB 每 3 小时同步至 MongoDB 生成网络质量报表。
4. 配置 Pod 亲和性策略，同业务 Pod 优先调度至同一可用区，减少跨区通信。
5. 建立网络基线：正常丢包率 < 0.1%，P99 延迟 < 50ms，超出即告警。
6. 对 eip 弹性公网 IP 配置带宽预警，使用率 > 80% 触发自动扩容。

## 预防措施

1. **MTU 标准化**：所有节点装机时统一设置 MTU=1450，Ansible 初始化脚本固化配置，避免跨 AZ 不一致。
2. **网络基线监控**：XXL-JOB 每 3 小时同步 VM 网络指标至 MongoDB，建立丢包率/延迟基线，偏离 2sigma 自动告警。
3. **Pod 亲和性策略**：同业务 Pod 优先同 AZ 调度，通过 `podAntiAffinity` 减少跨区流量。
4. **网卡缓冲区调优**：高流量节点配置 `ethtool -G eth0 rx 4096 tx 4096` 增大 ring buffer，减少 FIFO 丢包。
5. **网络设备巡检**：netDevice 产品线定期采集交换机端口丢包/错包指标，纳入 200+ 指标巡检矩阵。

## 相关 PromQL 速查

```promql
# 接收丢包率
rate(node_network_receive_drop_total[5m]) / rate(node_network_receive_packets_total[5m])

# 发送丢包率
rate(node_network_transmit_drop_total[5m]) / rate(node_network_transmit_packets_total[5m])

# 错包率
rate(node_network_receive_errs_total[5m]) / rate(node_network_receive_packets_total[5m])

# 带宽使用率
rate(node_network_receive_bytes_total[5m]) * 8 / on(instance) node_network_speed_bytes

# P99 延迟
histogram_quantile(0.99, sum(rate(request_duration_seconds_bucket[5m])) by (le))

# TCP 重传率
rate(node_netstat_Tcp_RetransSegs[5m]) / rate(node_netstat_Tcp_OutSegs[5m])

# 网卡 FIFO 丢包
rate(node_network_receive_fifo_total[5m])

# 网络设备端口丢包（netDevice）
rate(netdevice_port_in_discards[5m])
```
