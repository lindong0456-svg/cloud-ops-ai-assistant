# Docker 网络不通排障

## 故障现象

容器间无法通信，服务间接口调用超时。容器内无法解析外部域名，`ping` 和 `curl` 均失败。Kubernetes 集群内 Service DNS 解析异常，Pod 间网络隔离，跨节点 Pod 通信中断。在 MSP 平台中，dispatch（9091）→ cspm → adapter 采集链路因网络问题中断，监控数据无法正常流转。`kubectl exec` 进入容器后执行 `curl http://<service>:<port>` 返回连接超时或连接拒绝。

## 影响评估

网络故障属于 P1 级故障，影响范围通常为整个集群或特定网络区域。MSP 平台 dispatch → cspm → adapter 链路中任一环节网络不通，将导致监控数据采集中断、告警规则失效、大盘数据缺失。湖南政务云开发环境通过 SSH 隧道连接远端 MongoDB 和 Redis，隧道断开将导致所有依赖数据库的服务不可用，影响开发联调进度。跨节点 Pod 通信中断时，集群内分布式服务调用全部失败，影响面可达全量租户。

## 关联组件

- **容器网络**：Docker bridge、Calico/Flannel（K8s CNI）
- **DNS 服务**：CoreDNS（kube-system 命名空间）
- **网络隧道**：SSH 隧道（湖南政务云 `ssh -N -L 28047:... -L 8023:... root@10.10.102.247`）
- **依赖中间件**：MongoDB（端口 28047）、Redis（端口 8023）
- **业务链路**：dispatch（9091）→ cspm → adapter
- **网络配置**：iptables NAT 规则、/etc/resolv.conf、Pod CIDR

## 常见原因

1. **Bridge 网络冲突**：Docker 默认 bridge 网段（172.17.0.0/16）与宿主机或集群 Pod CIDR 冲突，导致路由异常，容器流量被错误路由。
2. **端口映射错误**：`docker run -p` 端口映射配置错误，或绑定地址仅 127.0.0.1 可达，外部无法访问。
3. **DNS 解析失败**：CoreDNS 异常或 Pod `/etc/resolv.conf` 配置错误，nameserver 未指向 CoreDNS ClusterIP，无法解析服务名。
4. **容器间网络隔离**：容器分属不同 bridge 网络，未通过自定义网络互联，导致互相不可达。
5. **SSH 隧道断开**：湖南政务云环境通过 SSH 隧道代理访问 MongoDB 和 Redis，隧道进程退出后 `localhost:28047` 和 `localhost:8023` 不可达。
6. **iptables 规则异常**：Docker NAT 规则被手动修改或被防火墙规则覆盖，导致流量转发失败。
7. **CNI 插件故障**：Calico/Flannel Pod 异常或节点间隧道不通，跨节点 Pod 通信中断。

## 排障步骤

1. **查看容器网络配置**：
   ```bash
   docker network ls
   docker network inspect <network_name>
   ```
   预期输出：显示网络子网、网关和连接的容器列表，确认网段无冲突。

2. **测试容器间连通性**：
   ```bash
   docker exec -it <container> ping <target_container_ip>
   docker exec -it <container> curl -v http://<target_service>:<port>/health
   ```
   预期输出：连通时返回响应，不通时显示 "Destination Host Unreachable" 或超时。

3. **检查 DNS 解析**：
   ```bash
   # 检查 CoreDNS 状态
   kubectl get pods -n kube-system -l k8s-app=kube-dns
   # 进入容器检查 resolv.conf
   kubectl exec -it <pod> -n <ns> -- cat /etc/resolv.conf
   # 测试域名解析
   kubectl exec -it <pod> -n <ns> -- nslookup <service_name>
   ```
   预期输出：resolv.conf 中 nameserver 应指向 CoreDNS ClusterIP，nslookup 应返回 Service ClusterIP。

4. **检查 iptables NAT 规则**：
   ```bash
   iptables -t nat -L -n -v | grep -i docker
   iptables -t nat -L DOCKER -n -v
   ```
   预期输出：确认 Docker DNAT 和 SNAT 规则存在且正确，流量可正常转发。

5. **检查 SSH 隧道连通性**（湖南政务云环境）：
   ```bash
   # 检查隧道端口是否监听
   netstat -tlnp | grep -E '28047|8023'
   # 测试 MongoDB 连通性
   curl -v telnet://localhost:28047
   # 测试 Redis 连通性
   redis-cli -h localhost -p 8023 ping
   ```
   预期输出：端口监听说明隧道正常，无监听说明隧道已断开需重建。

6. **重建 SSH 隧道**：
   ```bash
   ssh -N -L 28047:10.10.102.247:28047 -L 8023:10.10.102.247:8023 root@10.10.102.247
   # 后台运行
   nohup ssh -N -L 28047:10.10.102.247:28047 -L 8023:10.10.102.247:8023 root@10.10.102.247 &
   ```

7. **检查 CNI 插件状态**：
   ```bash
   kubectl get pods -n kube-system | grep -E 'calico|flannel'
   kubectl logs <cni_pod> -n kube-system
   ```

## 真实案例

**故障时间线**：

- **T+0**：湖南政务云开发环境，MSP 平台多个服务突然报 MongoDB 和 Redis 连接超时，cspm 服务日志出现 "com.mongodb.MongoSocketOpenException: Exception opening socket"。
- **T+2min**：运维执行 `kubectl get po -n msp-dev | grep cspm`，Pod 状态为 Running 但健康检查失败。
- **T+5min**：进入容器测试连通性：`kubectl exec -it <pod> -n msp-dev -- curl -v telnet://localhost:28047`，连接被拒绝（Connection refused）。
- **T+8min**：执行 `netstat -tlnp | grep -E '28047|8023'`，无输出，确认 SSH 隧道进程已退出。隧道命令为 `ssh -N -L 28047:10.10.102.247:28047 -L 8023:10.10.102.247:8023 root@10.10.102.247`。
- **T+10min**：同时发现 CoreDNS 间歇性解析失败，`kubectl get pods -n kube-system -l k8s-app=kube-dns` 显示 coredns Pod 仅 1 个副本且所在节点刚重启。
- **T+15min**：根因确认——SSH 隧道因远端 10.10.102.247 网络抖动导致连接断开，隧道进程退出；开发环境 CoreDNS 因节点重启后未及时恢复，导致服务名解析也间歇性失败。

**根因分析**：湖南政务云开发环境的 MongoDB 和 Redis 部署在远端 10.10.102.247，通过 SSH 隧道转发至本地 28047 和 8023 端口供容器使用。使用普通 `ssh -N -L` 命令建立隧道，未配置保活参数和自动重连机制，网络抖动时隧道进程直接退出。同时 CoreDNS 部署为单副本，所在节点重启后 Pod 未自动调度，DNS 解析中断约 5 分钟。dispatch → cspm → adapter 链路中 cspm 无法读取 MongoDB 配置数据，导致采集任务异常。故障影响开发环境约 25 分钟。

**处置步骤**：
1. 立即重建 SSH 隧道：`nohup ssh -N -L 28047:10.10.102.247:28047 -L 8023:10.10.102.247:8023 root@10.10.102.247 &`。
2. 重启 CoreDNS Pod：`kubectl delete pod <coredns_pod> -n kube-system`，触发重新调度。
3. 验证连通性：`redis-cli -h localhost -p 8023 ping` 返回 PONG，MongoDB 连接恢复。
4. 安装 autossh 替代普通 ssh，配置自动重连。

## 处置建议

网络配置标准化：所有跨环境连接使用统一的自定义 Docker 网络而非默认 bridge，避免网络段冲突。SSH 隧道使用 `autossh` 替代普通 `ssh`，配置自动重连参数 `ServerAliveInterval=30` 和 `ServerAliveCountMax=3`，防止隧道断开后无法自动恢复。编写隧道监控脚本，每分钟检测端口 28047 和 8023 的连通性，断连时自动重建隧道并发送告警。CoreDNS 部署多副本并配置 PodDisruptionBudget，确保节点重启后 DNS 服务优先恢复。

## 预防措施

1. **SSH 隧道保活**：使用 autossh 替代 ssh，配置参数：
   ```bash
   autossh -M 0 -N -o "ServerAliveInterval=30" -o "ServerAliveCountMax=3" \
     -L 28047:10.10.102.247:28047 -L 8023:10.10.102.247:8023 root@10.10.102.247
   ```
2. **隧道监控脚本**：每分钟检测端口连通性，断连自动重建并告警：
   ```bash
   #!/bin/bash
   nc -z localhost 28047 || { echo "MongoDB隧道断开" | mail -s "告警" ops@team; autossh -M 0 -N ... & }
   ```
3. **CoreDNS 高可用**：部署 2+ 副本，配置 PodDisruptionBudget，确保节点重启后 DNS 优先恢复。
4. **网络段规划**：Docker bridge 网段与 K8s Pod CIDR、Service CIDR 分离规划，避免冲突。
5. **网络连通性巡检**：定期执行容器间 ping 和 curl 测试，纳入日常巡检。

## 相关命令速查

```bash
# 查看容器网络
docker network ls
docker network inspect <network_name>

# 测试容器间连通性
docker exec -it <container> ping <target_ip>
docker exec -it <container> curl -v http://<service>:<port>

# DNS 排查
kubectl get pods -n kube-system -l k8s-app=kube-dns
kubectl exec -it <pod> -n <ns> -- cat /etc/resolv.conf
kubectl exec -it <pod> -n <ns> -- nslookup <service>

# iptables NAT 规则
iptables -t nat -L -n -v | grep docker

# SSH 隧道管理
netstat -tlnp | grep -E '28047|8023'
ssh -N -L 28047:10.10.102.247:28047 -L 8023:10.10.102.247:8023 root@10.10.102.247
autossh -M 0 -N -o "ServerAliveInterval=30" -o "ServerAliveCountMax=3" \
  -L 28047:10.10.102.247:28047 -L 8023:10.10.102.247:8023 root@10.10.102.247

# CNI 插件状态
kubectl get pods -n kube-system | grep -E 'calico|flannel'
```
