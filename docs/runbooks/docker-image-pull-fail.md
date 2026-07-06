# 镜像拉取失败排障

## 故障现象

Kubernetes Pod 状态长时间停留在 ImagePullBackOff 或 ErrImagePull。`kubectl get pods -n <namespace>` 输出显示 Pod 状态为 ImagePullBackOff，READY 列为 `0/1`。`kubectl describe pod <pod_name> -n <namespace>` 的 Events 部分出现 "Failed to pull image"、"rpc error: code = Unknown desc = failed to resolve image" 或 "not found" 等错误。业务服务无法正常部署和调度，Helm 发布后服务长时间不可用。

## 影响评估

镜像拉取失败属于 P1 级故障，直接阻断服务部署和版本迭代。在 MSP 平台多环境架构中，若生产环境（msp-prod）镜像拉取失败，将导致服务无法升级或回滚，业务持续运行在旧版本，无法修复已知缺陷。ARM 架构节点（西安政务云）拉取失败时，该节点上所有 Pod 无法调度，影响该区域全部业务。若多节点同时出现拉取失败，可能触发集群级调度异常。

## 关联组件

- **镜像仓库**：172.25.1.250:5000（x86 主仓库）、172.25.4.250:5000（x86 备仓库）、172.25.5.222:5000（ARM 仓库）、172.25.35.250:5000（POC 环境）
- **容器运行时**：containerd + crictl（K8s 节点）
- **构建工具**：package.sh（支持 x86 和 ARM 双架构构建）
- **部署工具**：Helm + Kubernetes
- **架构差异**：x86（amd64）节点与 ARM（arm64）节点共存
- **认证组件**：imagePullSecrets、docker registry credentials

## 常见原因

1. **仓库认证失败**：imagePullSecrets 未配置或凭证过期，无法登录私有镜像仓库。`kubectl describe pod` 显示 "authentication required" 或 "unauthorized"。
2. **网络不通**：节点与镜像仓库（如 172.25.1.250:5000、172.25.4.250:5000）之间网络不通或防火墙拦截 5000 端口。
3. **镜像不存在**：镜像 tag 拼写错误或未推送至对应仓库。`package.sh` 构建后忘记 push，或 push 到了错误的仓库地址。
4. **架构不匹配**：ARM 节点拉取 x86 架构镜像，`kubectl describe pod` 显示 "no matching manifest for linux/arm64 in the manifest list"。
5. **仓库负载过高**：Harbor 或本地 registry 并发拉取请求过多，返回 429 Too Many Requests 或超时。
6. **DNS 解析异常**：节点无法解析镜像仓库域名（如使用域名而非 IP 时）。

## 排障步骤

1. **查看 Pod 事件详情**：
   ```bash
   kubectl describe pod <pod_name> -n <namespace>
   ```
   预期输出：Events 部分显示具体拉取失败原因，如 "Failed to pull image 172.25.1.250:5000/cspm:v2.1.0: rpc error"。

2. **检查 imagePullSecrets 配置**：
   ```bash
   kubectl get secret <secret_name> -n <namespace> -o yaml
   ```
   预期输出：`.dockerconfigjson` 字段包含 base64 编码的仓库认证信息，确认仓库地址和账号密码正确。

3. **登录目标节点手动测试拉取**：
   ```bash
   # SSH 到目标节点后执行
   crictl pull 172.25.1.250:5000/cspm:v2.1.0
   # 或使用 docker
   docker pull 172.25.1.250:5000/cspm:v2.1.0
   ```
   预期输出：若认证失败提示 "unauthorized"，若网络不通提示 "connection refused"，若镜像不存在提示 "not found"。

4. **检查镜像架构信息**：
   ```bash
   docker manifest inspect 172.25.5.222:5000/cspm:v2.1.0-arm64
   ```
   预期输出：确认 manifest 中 `architecture` 字段为 `arm64` 还是 `amd64`，与目标节点架构匹配。

5. **排查节点到仓库的网络连通性**：
   ```bash
   curl -k https://172.25.1.250:5000/v2/_catalog
   telnet 172.25.1.250 5000
   # 或使用 nc
   nc -zv 172.25.1.250 5000
   ```
   预期输出：连通时返回仓库镜像列表，不通时提示 "Connection refused" 或超时。

6. **检查节点架构**：
   ```bash
   uname -m
   ```
   预期输出：x86 节点显示 `x86_64`，ARM 节点（华为鲲鹏）显示 `aarch64`。

7. **验证镜像是否已推送**：
   ```bash
   # 在构建机上检查
   docker images | grep <image_name>
   curl -k https://172.25.1.250:5000/v2/<repo>/tags/list
   ```

## 真实案例

**故障时间线**：

- **T+0**：西安政务云 ARM 节点部署 cspm 服务，通过 Helm 发布至 msp-prod 命名空间，镜像地址配置为 `172.25.1.250:5000/cspm:v2.1.0`。
- **T+1min**：`kubectl get po -n msp-prod | grep cspm` 显示 Pod 状态为 ErrImagePull，随后转为 ImagePullBackOff。
- **T+3min**：执行 `kubectl describe pod <pod> -n msp-prod`，Events 显示 "no matching manifest for linux/arm64 in the manifest list"。
- **T+8min**：SSH 登录 ARM 节点，执行 `uname -m` 确认架构为 `aarch64`（华为鲲鹏）。执行 `crictl pull 172.25.1.250:5000/cspm:v2.1.0`，报错 "manifest not found"。
- **T+12min**：在构建机执行 `docker manifest inspect 172.25.1.250:5000/cspm:v2.1.0`，确认 manifest 仅包含 `linux/amd64` 架构层。
- **T+15min**：确认根因——`package.sh` 默认在 x86 构建机上构建镜像，产物仅包含 amd64 架构层，而西安政务云节点为 ARM 架构。

**根因分析**：`package.sh` 构建脚本未支持多架构构建，默认使用 x86 构建机生成 amd64 镜像并推送至 172.25.1.250:5000。西安政务云使用华为鲲鹏 ARM 服务器，需要 arm64 架构镜像。ARM 镜像需推送到独立的 ARM 镜像仓库 172.25.5.222:5000，且推送前需对 ARM tar 包进行重命名以区分架构（如 `cspm-v2.1.0-arm64.tar`）。部署 YAML 中未通过 nodeSelector 区分架构，导致 ARM 节点尝试拉取 x86 镜像。故障持续约 30 分钟，西安政务云区域 cspm 服务完全不可用。

**处置步骤**：
1. 在 ARM 构建机上重新构建 cspm 镜像：`sh package.sh v2.1.0 v2.1.0-arm64 cspm cspm-service`，指定 ARM 平台。
2. 对 ARM tar 包重命名：`mv cspm-v2.1.0.tar cspm-v2.1.0-arm64.tar`，加载并推送至 ARM 仓库：
   ```bash
   docker load -i cspm-v2.1.0-arm64.tar
   docker tag cspm:v2.1.0 172.25.5.222:5000/cspm:v2.1.0-arm64
   docker push 172.25.5.222:5000/cspm:v2.1.0-arm64
   ```
3. 更新 Helm values，ARM 节点部署使用 `172.25.5.222:5000/cspm:v2.1.0-arm64`，添加 `nodeSelector: kubernetes.io/arch: arm64`。
4. 重新执行 Helm 部署，确认 Pod 恢复 Running。

## 处置建议

针对 ARM 场景，使用 `docker buildx` 构建多架构镜像，或分别在 x86 和 ARM 构建机上构建对应架构镜像并推送至各自仓库（x86 推至 172.25.1.250:5000，ARM 推至 172.25.5.222:5000）。在部署 YAML 中通过 nodeSelector 指定架构标签，确保 ARM 节点拉取 ARM 镜像。建议在 `package.sh` 中增加架构参数，构建时自动选择目标平台并打上 `arm64`/`amd64` 后缀 tag，从源头避免架构不匹配问题。

## 预防措施

1. **多架构构建**：`package.sh` 增加 `--platform` 参数，支持 `linux/amd64` 和 `linux/arm64` 双架构构建，使用 `docker buildx` 生成多架构 manifest。
2. **仓库隔离**：x86 镜像推送至 172.25.1.250:5000，ARM 镜像推送至 172.25.5.222:5000，tag 中带架构后缀。
3. **部署校验**：Helm chart 中为 ARM 节点添加 `nodeSelector: kubernetes.io/arch: arm64`，防止跨架构误调度。
4. **预拉取验证**：部署前在目标节点执行 `crictl pull <image>` 预验证镜像可拉取且架构匹配。
5. **仓库监控**：对 172.25.1.250:5000、172.25.5.222:5000 等仓库配置健康检查和可用性告警。

## 相关命令速查

```bash
# 查看 Pod 拉取失败详情
kubectl describe pod <pod> -n <ns>

# 检查 imagePullSecrets
kubectl get secret <secret> -n <ns> -o yaml

# 手动拉取测试
crictl pull 172.25.1.250:5000/<image>:<tag>
docker pull 172.25.1.250:5000/<image>:<tag>

# 检查镜像架构
docker manifest inspect <image>:<tag>

# 检查节点架构
uname -m

# 网络连通性测试
curl -k https://172.25.1.250:5000/v2/_catalog
nc -zv 172.25.1.250 5000

# 构建并推送 ARM 镜像
sh package.sh <base-ver> <full-ver>-arm64 <project> <service>
docker tag <image>:<tag> 172.25.5.222:5000/<image>:<tag>-arm64
docker push 172.25.5.222:5000/<image>:<tag>-arm64

# 查看仓库镜像列表
curl -k https://172.25.1.250:5000/v2/<repo>/tags/list
```
