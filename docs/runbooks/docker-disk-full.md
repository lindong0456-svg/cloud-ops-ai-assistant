# Docker 磁盘满排障

## 故障现象

节点磁盘使用率超过 90%，`docker pull` 报错 "no space left on device"，容器写入操作失败返回 "disk full" 或 "read-only file system"。Kubelet 无法创建新 Pod，`kubectl get pods -n <namespace>` 显示大量 Pod 处于 Pending 或 Evicted 状态。`df -h` 显示 /var 或 /var/lib/docker 挂载点使用率接近 100%。节点状态变为 NotReady，Kubernetes 集群触发节点驱逐。

## 影响评估

磁盘满属于 P0 级故障，影响节点上所有容器和 Pod。MSP 平台单节点通常运行 10-20 个微服务 Pod，磁盘满导致：容器日志无法写入、镜像无法拉取更新、Pod 被驱逐至其他节点引发重新调度风暴、containerd 运行时异常甚至节点 NotReady。若多节点同时磁盘满，集群可用容量急剧下降，可能导致 Pod 无节点可调度，业务全面中断。POC7 环境曾因磁盘满导致 5 个节点同时 NotReady，影响 60+ Pod。

## 关联组件

- **存储路径**：/var/lib/docker（docker）、/var/lib/containerd（containerd）、/var/log（容器日志）
- **镜像仓库**：172.25.35.250:5000（POC 环境）、172.25.1.250:5000（生产环境）
- **构建工具**：package.sh（每次构建产生新镜像层）
- **批量管理**：Ansible + crictl（`ansible all -i /etc/kubeos/multinode -m shell -a "crictl rmi <image>"`）
- **K8s 组件**：Kubelet（节点驱逐）、containerd（运行时）
- **日志系统**：containerd json-log、journald

## 常见原因

1. **废弃镜像堆积**：多版本镜像未及时清理，MSP 平台 20+ 微服务每次发布通过 `package.sh` 构建新版本镜像并推送至 172.25.35.250:5000，但旧版本镜像从未清理，每个服务堆积 5-8 个版本。
2. **容器日志未轮转**：containerd 默认未配置日志轮转，容器 stdout/stderr 日志无限增长，单个日志文件可达数十 GB。
3. **构建缓存堆积**：频繁 `docker build` 产生大量中间层缓存和悬空镜像（dangling images）未清理。
4. **Volume 未清理**：废弃的匿名 volume 和 bind mount 残留，`docker volume ls` 中存在大量 dangling volume。
5. **系统日志膨胀**：journald 日志未限制大小，/var/log/journal 持续增长。
6. **Coredump 文件**：容器崩溃产生的 core 文件未清理，占用大量空间。

## 排障步骤

1. **确认磁盘使用率**：
   ```bash
   df -h
   df -h /var/lib/containerd
   ```
   预期输出：定位使用率超过 90% 的挂载点，通常为 /var 或 /var/lib/containerd。

2. **查看 Docker 资源占用明细**：
   ```bash
   docker system df -v
   ```
   预期输出：显示 Images、Containers、Local Volumes、Build Cache 各类资源占用大小，`-v` 显示每个镜像和容器的详细大小。

3. **查找大文件**：
   ```bash
   du -sh /var/lib/containerd/* | sort -rh | head -20
   find /var/lib/containerd -name "*.log" -size +500M -exec ls -lh {} \;
   ```
   预期输出：定位占用空间最大的目录和日志文件。

4. **查看悬空镜像**：
   ```bash
   docker images -f "dangling=true"
   crictl images | grep <none>
   ```
   预期输出：列出无 tag 的中间构建层镜像。

5. **检查容器日志大小**：
   ```bash
   ls -lhS /var/lib/docker/containers/*/*-json.log | head -10
   # containerd 日志
   crictl logs --tail 0 <container_id> 2>&1 | head -1
   ```
   预期输出：按大小排序显示最大的容器日志文件。

6. **使用 Ansible 批量清理废弃镜像**：
   ```bash
   # 清理所有无 tag 镜像
   ansible all -i /etc/kubeos/multinode -m shell -a "crictl rmi \$(crictl images -q | head -20)"
   # 清理特定服务的旧版本镜像
   ansible all -i /etc/kubeos/multinode -m shell -a "crictl rmi 172.25.35.250:5000/cspm:v2.0.0"
   ```
   预期输出：各节点返回删除的镜像 ID 列表和释放的空间大小。

7. **清理容器日志**：
   ```bash
   # 截断超大日志文件
   truncate -s 0 /var/lib/docker/containers/*/*-json.log
   # 或配置日志轮转后重启 containerd
   ```

8. **清理构建缓存和悬空资源**：
   ```bash
   docker image prune -f
   docker builder prune -f
   docker volume prune -f
   ```

## 真实案例

**故障时间线**：

- **T+0**：POC7 环境监控告警，node-03、node-05、node-07 磁盘使用率超过 92%，触发 P0 告警。
- **T+2min**：运维登录 node-03，执行 `df -h` 确认 /var 挂载点使用率 94%，可用空间仅 12GB。
- **T+5min**：执行 `docker system df -v`，显示 Images 占用 210GB，Containers 占用 35GB，Build Cache 占用 28GB。发现 cspm、dispatch、adapter 等 20+ 微服务各有 5-8 个历史版本镜像。
- **T+10min**：执行 `ls -lhS /var/lib/docker/containers/*/*-json.log | head -10`，发现 3 个容器日志文件超过 10GB，最大一个 15GB（cspm-ms-monitorservice）。
- **T+15min**：确认 containerd 未配置日志轮转，日志无限增长。同时 `package.sh` 构建流程未包含旧镜像清理步骤。
- **T+20min**：使用 Ansible 批量清理废弃镜像：
  ```bash
  ansible all -i /etc/kubeos/multinode -m shell -a "crictl rmi \$(crictl images | grep 'v1\.' | awk '{print \$3}')"
  ```
  释放约 150GB 空间，各节点磁盘使用率降至 55%。
- **T+30min**：截断超大日志文件，配置 containerd 日志轮转参数。

**根因分析**：MSP 平台 20+ 微服务每次发布通过 `package.sh` 构建新版本镜像并推送至 172.25.35.250:5000，但旧版本镜像从未清理，每个服务堆积 5-8 个版本，镜像总量超过 200GB。containerd 默认未配置日志轮转（`max_log_size` 未设置），部分高流量服务日志单文件超过 10GB。`package.sh` 构建过程中产生的 dangling 镜像层也未清理。磁盘满导致 Kubelet 驱逐 Pod，5 个节点状态变为 NotReady，60+ Pod 被重新调度。

**处置步骤**：
1. Ansible 批量清理废弃镜像和悬空镜像层。
2. 截断超大容器日志文件，释放即时空间。
3. 配置 containerd 日志轮转：设置 `max_log_size=100MB`、`max_log_count=3`。
4. 编写定期清理脚本加入 crontab，每周执行 `docker image prune -a --filter "until=168h"`。
5. 在 `package.sh` 构建流程中增加 `docker image prune -f` 步骤。

## 处置建议

制定镜像保留策略：每个服务最多保留最近 3 个版本镜像，超期镜像通过定时脚本自动清理。配置 containerd 日志轮转：设置 `max_log_size=100MB` 和 `max_log_count=3`。编写定期清理脚本加入 crontab，每周执行 `docker image prune -a --filter "until=168h"` 清理一周前的镜像。同时在 `package.sh` 构建流程中增加 `docker image prune -f` 步骤，构建后自动清理本机无用镜像层，从源头控制磁盘增长。

## 预防措施

1. **镜像保留策略**：每个服务最多保留最近 3 个版本镜像，编写脚本自动清理超期镜像。
2. **日志轮转配置**：containerd 配置 `/etc/containerd/config.toml` 中设置日志轮转参数：
   ```toml
   [plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc]
   max_log_size = "100MB"
   max_log_count = 3
   ```
3. **定时清理任务**：crontab 配置每周清理任务：
   ```bash
   0 2 * * 0 docker image prune -a --filter "until=168h" --force
   ```
4. **构建清理**：`package.sh` 增加 `docker image prune -f` 步骤，构建后自动清理。
5. **磁盘监控告警**：设置磁盘使用率 80% 告警阈值，提前介入处理。

## 相关命令速查

```bash
# 磁盘使用率
df -h

# Docker 资源占用
docker system df -v

# 查找大文件
du -sh /var/lib/containerd/* | sort -rh | head -20
find /var/lib/containerd -name "*.log" -size +500M

# 查看悬空镜像
docker images -f "dangling=true"
crictl images | grep <none>

# 容器日志大小
ls -lhS /var/lib/docker/containers/*/*-json.log | head -10

# Ansible 批量清理镜像
ansible all -i /etc/kubeos/multinode -m shell -a "crictl rmi <image>"

# 清理悬空资源
docker image prune -f
docker builder prune -f
docker volume prune -f

# 截断超大日志
truncate -s 0 /var/lib/docker/containers/*/*-json.log

# 定时清理一周前镜像
docker image prune -a --filter "until=168h" --force
```
