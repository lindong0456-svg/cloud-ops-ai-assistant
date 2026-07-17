# cloud-ops-ai-assistant 权限控制验证集（APIPost 可导入）

> 后端地址默认 `http://localhost:8080`，如你 IDEA 里跑的是别的端口，把下面所有 `localhost:8080` 全局替换即可。
> 所有账号密码均为 `admin123`。
> APIPost 导入方式：新建请求 → 右上角「导入」→ 粘贴下面的 curl（或保存为 `.sh` 直接拖入）。

---

## 一、权限矩阵（先记住这个，后面验证对照用）

| 账号 | 角色 | 拥有的权限（中文） | 关键缺失 |
|------|------|-------------------|----------|
| `admin` | 超级管理员 | 全部 9 项 | 无 |
| `tenant_admin` | 租户管理员 | 告警查看/告警处理/资源查看/账单查看/知识检索/知识入库/Agent对话/SOP查看 | 无用户管理 |
| `ops_eng` | 运维工程师 | 告警查看/告警处理/资源查看/**知识检索**/Agent对话/SOP查看 | **无账单查看** |
| `ops_viewer` | 运维查看者 | 告警查看/资源查看/知识检索/SOP查看 | **无 Agent对话**、无账单查看 |
| `finance` | 财务用户 | **账单查看**/Agent对话 | **无知识检索**、无告警查看、无资源查看 |

**Tool → 所需权限 映射：**

| Tool 方法名 | 所需权限 | ops_eng | finance |
|-------------|---------|:---:|:---:|
| `queryAlarms` / `queryAlarmsByResource` | `alarm:read` | ✅ | ❌ |
| `queryBill` / `queryBillByResource` | `billing:read` | ❌ | ✅ |
| `queryLoad` / `queryLatestLoad` / `queryRelation` / `queryRelationByType` | `resource:read` | ✅ | ❌ |
| `searchKnowledge` | `rag:read` | ✅ | ❌ |
| `/api/agent/chat*` | `agent:chat` | ✅ | ✅（ops_viewer ❌）|

**权限拦截行为（重要）：** Tool 仍会被大模型调用，但 `AbstractTool.execute()` 在执行业务前校验权限，无权限时 `tool-end` 的 `result` 返回 `"权限不足：需要 xxx 权限"`，大模型据此给出通用回答。所以验证信号是 **tool-end 的 result 内容**，不是"有没有 tool-start"。

---

## 二、A 组：登录接口验证（权限列表是否正确返回）

### A1 · admin 登录 —— 预期返回全部 9 项权限
```bash
curl -X POST 'http://localhost:8080/api/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
```
> 预期：`permissions` 数组含 `billing:read`(账单查看)、`rag:read`(知识检索) 等共 9 项，且每项带 `name` 中文。

### A2 · ops_eng 登录 —— 预期「有知识检索、无账单查看」
```bash
curl -X POST 'http://localhost:8080/api/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"ops_eng","password":"admin123"}'
```
> 预期：含 `rag:read`/`resource:read`/`alarm:read`，**不含** `billing:read`。

### A3 · finance 登录 —— 预期「只有账单查看 + Agent对话」
```bash
curl -X POST 'http://localhost:8080/api/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"finance","password":"admin123"}'
```
> 预期：`permissions` 仅 2 项 —— `billing:read`(账单查看) 与 `agent:chat`(Agent对话)，**不含** `rag:read`/`alarm:read`/`resource:read`。

### A4 · ops_viewer 登录 —— 预期「无 Agent对话」
```bash
curl -X POST 'http://localhost:8080/api/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"ops_viewer","password":"admin123"}'
```
> 预期：无 `agent:chat`。这决定了它在 B5 调对话接口会被 403。

> 💡 实操建议：先跑 A1~A4，把各自返回的 `token` 复制出来，填到下面 B 组 curl 的 `token=` 参数里。

---

## 三、B 组：对话 + Tool 权限验证（核心验证点）

> 用 SSE 流式接口 `GET /api/agent/chat/stream`，`curl -N` 看实时事件。
> `userId` 填对应账号（admin→user-admin, ops_eng→user-ops, finance→user-finance）。
> `token` 填 A 组拿到的。

### B1 · ops_eng 问账单 —— 预期「触发 queryBill，但 result 为权限不足」
```bash
curl -N 'http://localhost:8080/api/agent/chat/stream?userId=user-ops&message=查一下tenant-001上个月的账单流水&token=OPS_ENG_TOKEN'
```
> 预期 SSE 事件流：
> - `tool-start` → `toolName: "queryBill"`
> - `tool-end` → `result` 含 **「权限不足：需要 billing:read 权限」**（不是真实账单数据）
> - 最终 `token` 回复为通用话术，未泄露账单。

### B2 · finance 问账单 —— 预期「触发 queryBill，返回真实数据」
```bash
curl -N 'http://localhost:8080/api/agent/chat/stream?userId=user-finance&message=查一下tenant-001上个月的账单流水&token=FINANCE_TOKEN'
```
> 预期 SSE：
> - `tool-start` → `toolName: "queryBill"`
> - `tool-end` → `result` 为**真实账单明细**（资源名称、计费类型、费用金额）。
> ✅ 与 B1 对比，验证账单权限隔离生效。

### B3 · ops_eng 问知识库（CPU 高）—— 预期「触发 searchKnowledge，返回真实 SOP」
```bash
curl -N 'http://localhost:8080/api/agent/chat/stream?userId=user-ops&message=CPU使用率很高怎么处理&token=OPS_ENG_TOKEN'
```
> 预期 SSE：
> - `tool-start` → `toolName: "searchKnowledge"`
> - `tool-end` → `result` 为**真实 SOP 处置方案**（ops_eng 有 `rag:read`）。

### B4 · finance 问知识库（CPU 高）—— 预期「触发 searchKnowledge，但 result 为权限不足」
```bash
curl -N 'http://localhost:8080/api/agent/chat/stream?userId=user-finance&message=CPU使用率很高怎么处理&token=FINANCE_TOKEN'
```
> 预期 SSE：
> - `tool-start` → `toolName: "searchKnowledge"`
> - `tool-end` → `result` 含 **「权限不足：需要 rag:read 权限」**（finance 无 `rag:read`）。
> ✅ 与 B3 对比，验证 RAG 权限隔离生效。

### B5 · ops_viewer 调对话接口 —— 预期 HTTP 403
```bash
curl -i -N 'http://localhost:8080/api/agent/chat/stream?userId=user-viewer&message=你好&token=OPS_VIEWER_TOKEN'
```
> 预期：HTTP 状态码 **403 Forbidden**（ops_viewer 无 `agent:chat`，被 `@PreAuthorize` 拦截）。

---

## 四、C 组：快速校验（非流式 JSON，适合断言）

如果不想看 SSE 事件流，可用 `/api/agent/chat`（GET，返回 JSON）做快捷验证：

### C1 · ops_eng 问账单（JSON 版）
```bash
curl 'http://localhost:8080/api/agent/chat?userId=user-ops&message=查一下tenant-001上个月的账单流水&token=OPS_ENG_TOKEN'
```
> 预期：`{"status":"success",...,"reply":"...权限不足/无法查询账单..."}` —— reply 中不应出现具体费用数字。

### C2 · finance 问账单（JSON 版）
```bash
curl 'http://localhost:8080/api/agent/chat?userId=user-finance&message=查一下tenant-001上个月的账单流水&token=FINANCE_TOKEN'
```
> 预期：reply 中出现具体资源名称 / 费用金额等真实账单数据。

---

## 五、验证结论速查表

| 验证点 | 请求 | 预期信号 | 通过标准 |
|--------|------|---------|----------|
| A1 管理员全权限 | 登录 admin | permissions 9 项 | 含 billing+rag |
| A2 运维无账单权 | 登录 ops_eng | 无 billing:read | 有 rag:read |
| A3 财务仅账单权 | 登录 finance | 仅 billing+agent:chat | 无 rag/alarm/resource |
| A4 查看者无对话权 | 登录 ops_viewer | 无 agent:chat | — |
| B1 运维查账单被拦 | chat/stream ops_eng 账单 | tool-end result=权限不足 | 无真实账单 |
| B2 财务查账单放行 | chat/stream finance 账单 | tool-end result=真实数据 | 有费用明细 |
| B3 运维查知识放行 | chat/stream ops_eng 知识 | tool-end result=真实SOP | 有处置方案 |
| B4 财务查知识被拦 | chat/stream finance 知识 | tool-end result=权限不足 | 无 SOP |
| B5 查看者禁对话 | chat/stream ops_viewer | HTTP 403 | 被拦截 |
