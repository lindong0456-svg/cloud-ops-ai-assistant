# 容器无法启动排障

## 故障现象

容器启动后立即退出（Exited 状态），或反复重启（CrashLoopBackOff）。`docker ps -a` 显示容器状态为 Exited (1)、Exited (127) 或 Restarting。Kubernetes 环境下 Pod 处于 CrashLoopBackOff 状态，`kubectl get pods -n <namespace>` 输出中 READY 列显示 `0/1`，RESTARTS 计数持续递增。业务接口不可达，上游调用方报连接拒绝或超时。在联通数科 MSP 平台中，dispatch（端口 9091）→ cspm → adapter 架构链路任一环节容器启动失败，均会导致整条监控采集链路中断。

## 影响评估

容器无法启动属于 P1 级故障，直接影响业务可用性。若 dispatch 服务容器启动失败，端口 9091 不可达，下游 cspm 服务无法接收监控数据推送，adapter 无法完成数据适配转换，最终导致 MSP 平台监控大盘数据缺失、告警规则失效。多个微服务同时启动失败时，可能引发雪崩效应，影响范围从单个服务扩展至整个集群。预计受影响用户量取决于故障服务的关键程度，核心服务故障影响全量租户。

## 关联组件

- **容器运行时**：docker / containerd（K8s 节点使用 containerd + crictl）
- **镜像仓库**：172.25.1.250:5000、172.25.4.250:5000、172.25.5.222:5000、172.25.35.250:5000
- **编排工具**：Kubernetes + Helm（MSP 平台使用 Helm chart 部署）
- **构建工具**：package.sh 脚本（`sh package.sh <base-version> <full-version> <project> <service>`）
- **业务链路**：dispatch（9091）→ cspm → adapter 数据采集链路
- **配置管理**：Nginx 反向代理、ConfigMap、Secret

## 常见原因

1. **ENTRYPOINT/CMD 配置错误**：Dockerfile 中 ENTRYPOINT 路径错误或参数缺失，导致主进程无法启动。ExitCode 127 通常表示命令未找到。
2. **端口冲突**：容器映射端口与宿主机已有服务端口冲突，绑定失败。常见于 dispatch 服务 9091 端口被其他进程占用。
3. **依赖文件缺失**：镜像内缺少运行所需的配置文件、证书或依赖库。Helm 部署时 ConfigMap 挂载路径与镜像内预期路径不一致。
4. **权限问题**：容器以非 root 用户运行，但挂载的 volume 或配置文件权限不足，导致读取配置失败。
5. **依赖服务不可达**：启动时尝试连接数据库、Redis 等依赖服务，连接超时导致进程退出。未配置合理健康检查和重试机制。
6. **资源不足**：节点内存或 CPU 资源不足，kubelet 无法调度 Pod，或容器 OOM 后被 kill。

## 排障步骤

1. **查看容器日志**：
   ```bash
   docker logs <container_id> --tail 100
   # 或 Kubernetes 环境
   kubectl logs <pod_name> -n <namespace> --previous
   ```
   预期输出：定位到具体报错信息，如 "connection refused"、"file not found"、"permission denied"。

2. **检查容器状态和退出码**：
   ```bash
   docker inspect <container_id> --format '{{.State.Status}} ExitCode:{{.State.ExitCode}} Error:{{.State.Error}}'
   # 或
   kubectl describe pod <pod_name> -n <namespace>
   ```
   预期输出：ExitCode 127 表示命令未找到，ExitCode 1 表示应用异常退出，ExitCode 137 表示 OOMKilled。

3. **审查 Dockerfile**：检查 ENTRYPOINT 和 CMD 指令路径是否正确，确认可执行文件在镜像内存在：
   ```bash
   docker run -it --entrypoint /bin/sh <image>:<tag> -c "ls -la /app/ && which <entrypoint_cmd>"
   ```

4. **检查端口占用**：
   ```bash
   netstat -tlnp | grep 9091
   ss -tlnp | grep 9091
   ```
   预期输出：若已有进程监听 9091，需停止冲突进程或修改容器端口映射。

5. **验证挂载卷权限**：
   ```bash
   docker run -it --entrypoint /bin/sh <image>:<tag> -c "id && ls -la /path/to/config"
   ```
   预期输出：确认容器运行用户 UID 和配置文件权限，确保有读权限。

6. **检查 Helm 部署配置**：
   ```bash
   helm list -n <namespace>
   helm status <release_name> -n <namespace>
   kubectl get configmap -n <namespace>
   ```
   确认 ConfigMap 中配置内容正确，挂载路径与镜像内路径匹配。

7. **批量重启服务 Pod**（MSP 平台常用）：
   ```bash
   kubectl get po -n msp-prod | grep <service> | awk '{print "kubectl -n msp-prod delete po "$1}' | sh
   ```

## 真实案例

**故障时间线**：

- **T+0**：MSP 平台 dispatch 服务通过 Helm 部署至 msp-prod 命名空间，`sh package.sh v2.1.0 v2.1.0.3 dispatch dispatch-service` 构建新镜像并推送至 172.25.1.250:5000。
- **T+2min**：运维人员执行 `kubectl get po -n msp-prod | grep dispatch`，发现 Pod 状态为 CrashLoopBackOff，RESTARTS 已达 5 次。
- **T+5min**：执行 `kubectl logs <pod> -n msp-prod --previous`，日志显示 Nginx 启动报错 `host not found in upstream`，主进程退出，ExitCode 为 1。
- **T+10min**：进入容器排查，执行 `docker run -it --entrypoint /bin/sh 172.25.1.250:5000/dispatch:v2.1.0.3`，检查 Nginx 配置文件 `/etc/nginx/conf.d/dispatch.conf`，发现 upstream 模块配置的后端服务端口为 8080。
- **T+15min**：确认实际 dispatch 服务监听端口为 18080，两者不一致导致 Nginx 反向代理无法解析后端地址，容器启动后立即退出。

**根因分析**：`package.sh` 构建脚本在生成 Nginx 配置时，upstream 端口使用了默认值 8080，而 dispatch 服务在本次版本中已将监听端口从 8080 变更为 18080。构建脚本未同步更新配置模板，且 CI 流程中缺少端口一致性校验。该问题在首次部署新版本时暴露，dispatch（9091）→ cspm → adapter 采集链路完全中断，MSP 平台监控数据缺失约 20 分钟。

**处置步骤**：
1. 修正 Nginx 配置中 upstream 端口为 18080，提交至配置仓库。
2. 重新执行 `sh package.sh v2.1.0 v2.1.0.4 dispatch dispatch-service` 构建新镜像并推送至 172.25.1.250:5000。
3. 执行 `kubectl get po -n msp-prod | grep dispatch | awk '{print "kubectl -n msp-prod delete po "$1}' | sh` 批量重启 Pod。
4. 确认 Pod 状态恢复 Running：`kubectl get po -n msp-prod | grep dispatch`。
5. 验证 9091 端口可达：`curl -s http://<pod_ip>:9091/health`，返回 200 OK。

## 处置建议

修正 Nginx 配置中 upstream 端口为实际服务端口 18080，重新构建镜像并推送至 172.25.1.250:5000 仓库，然后通过 kubectl 批量重启 Pod。建议在 `package.sh` 构建流程中加入端口一致性校验脚本，自动比对 Nginx upstream 配置与服务实际监听端口。将 Nginx upstream 配置模板化，构建时通过环境变量自动注入正确端口，避免硬编码。同时在 Helm values 中将关键端口参数化，部署时可通过 `--set` 覆盖。

## 预防措施

1. **配置模板化**：Nginx 配置使用 envsubst 模板，构建时自动注入服务端口，消除硬编码。
2. **CI 端口校验**：在 `package.sh` 中增加校验步骤，比对 Dockerfile EXPOSE、Nginx upstream、服务实际监听端口三者一致。
3. **健康检查**：Helm 部署 YAML 中配置 livenessProbe 和 readinessProbe，启动失败时快速反馈。
4. **灰度部署**：新版本先在 msp-test 命名空间验证启动正常，再推广至 msp-prod。
5. **配置审计**：定期扫描所有服务的 Nginx/ConfigMap 配置，检查端口引用一致性。

## 相关命令速查

```bash
# 查看容器退出日志
docker logs <container_id> --tail 100
kubectl logs <pod> -n <ns> --previous

# 检查容器退出码
docker inspect <container_id> --format '{{.State.ExitCode}}'
kubectl describe pod <pod> -n <ns>

# 进入容器排查
docker run -it --entrypoint /bin/sh <image>:<tag>

# 检查端口占用
netstat -tlnp | grep <port>
ss -tlnp | grep <port>

# Helm 部署状态
helm status <release> -n <ns>

# 批量重启 Pod
kubectl get po -n msp-prod | grep <service> | awk '{print "kubectl -n msp-prod delete po "$1}' | sh

# 构建镜像
sh package.sh <base-version> <full-version> <project> <service>

# 验证服务端口可达
curl -s http://<pod_ip>:<port>/health
```
