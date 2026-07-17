# cloud-ops-ai-assistant

云运维智能助手 —— 基于 Java AI Agent 的运维排障与数据查询平台（LangChain4j + RAG + RBAC + ReAct）。

一个能自主调用工具完成「告警排查 → 资源拓扑 → 负载分析 → SOP 检索 → 综合结论」的运维助手，并通过细粒度的 RBAC 权限体系实现多角色（运维 / 财务 / 只读）的数据与能力隔离。

---

## 核心特性

- **ReAct 自主排障**：告警 → 资源拓扑 → 负载趋势 → SOP 知识库 → 综合根因与处置建议，平均 3–4 次工具调用
- **RBAC 权限体系**（详见 [权限模型](#权限模型-rbac)）：接口级 `@PreAuthorize` + 工具级 `@RequiredPermission` + 数据级租户/部门隔离 + 前端动态渲染，四层防护
- **RAG 混合检索**：向量（Milvus HNSW）+ 关键词 + RRF 粗排 + Rerank 精排，两阶段召回
- **SSE 流式对话**：`TokenStream` + 结构化事件，排障过程透明化（思考 / 工具调用 / 观察 / 结论 四色卡片）
- **全链路可观测**：`ChatModelListener` + Micrometer + Prometheus + Grafana 看板，traceId 贯穿 chat→agent→tool→rag→llm
- **ChatMemory 持久化**：`MessageWindowChatMemory` 滑动窗口 20 轮 + MySQL 二次兜底
- **输入护轨 InputGuardrail**：拦截高危变更指令（重启 / 删除 / 扩容等）
- **自省重试 Self-Reflection**：工具返回空 / 异常时自动换关键词重试
- **MCP Server**：官方 SDK 独立进程（stdio 传输），可被 Claude Desktop 等 MCP 客户端调用

---

## 技术栈

| 层 | 技术 |
|----|------|
| 后端框架 | Spring Boot 3.4.1 + Java 21 |
| 安全 | Spring Security 6.4.2 + JJWT 0.12.6（JWT 认证） |
| AI 框架 | LangChain4j 1.0.0-beta3（ReAct Agent + Tool + RAG） |
| 向量库 | Milvus（standalone 模式，collection: `ops_knowledge`） |
| ORM | MyBatis-Plus 3.5.9 |
| 数据库 | MySQL 8（`cloud_ops` 库） |
| 大模型 | DeepSeek（对话，OpenAI 兼容）/ DashScope `text-embedding-v4`（向量，OpenAI 兼容） |
| 前端 | Vue 3 + Vite 8 + TypeScript |
| 可观测 | Micrometer + Prometheus + Grafana |

---

## 系统架构

### 模块划分（Maven 多模块，8 模块分层）

```
cloud-ops-parent/                      ← 父 POM（统一版本管理）
├── cloud-ops-common/    ← 公共层：AbstractTool、ToolResult、RequiredPermission 注解、ToolPermissionChecker、RequestContextHolder
├── cloud-ops-domain/    ← 领域层：Entity、Mapper、SQL（业务 Mock 数据 + 权限表 DDL）
├── cloud-ops-security/  ← 安全层：RBAC（JWT + Spring Security + 数据权限拦截器）
├── cloud-ops-rag/       ← 能力层：RAG 入库 + 混合检索 + Rerank 精排
├── cloud-ops-skill/     ← 能力层：纯 POJO Skill（构造器注入，零框架依赖）
├── cloud-ops-agent/     ← 协议层：@Tool 包装 Skill + Guardrail + Memory + System Prompt
├── cloud-ops-app/       ← 启动层：Spring Boot 主入口 + Controller（:8080）
└── cloud-ops-mcp/       ← MCP Server：独立进程，stdio 传输（官方 SDK）
```

### 模块依赖（严格自上而下，无环）

```
        app
       / | \ \  \
      /  |  \ \  \
   agent rag security skill domain
     |    |     |    |     |
     └────┴───common───────┘
                 |
                mcp（独立进程，依赖 domain + skill）
```

- `common`：唯一被全员依赖的基座，保持瘦身（工具基类 + 权限注解/接口 + 请求上下文持有器）
- `domain`：实体 + Mapper + SQL，无内部依赖
- `security`：RBAC 实现，依赖 `common`（运行时通过 `ToolPermissionChecker` 回调鉴权）
- `rag` / `skill`：纯能力层，依赖 `common` / `domain`
- `agent`：`@Tool` 包装 Skill，依赖 `common` + `skill` + `security`（工具权限检查）
- `app`：聚合所有模块，唯一可执行入口
- `mcp`：独立进程，不经过 Spring 上下文

### Skill 与 Tool 的关系（核心设计）

```
       @Tool(Agent 进程内)     MCP(跨进程 stdio)    REST(前端 HTTP)
              ↓                    ↓                   ↓
       ┌─────────────────────────────────────────────────┐
       │   Skill（纯 POJO，构造器注入，零框架依赖）       │
       └─────────────────────────────────────────────────┘
```

同一个 `AlarmSearchSkill` 可被 Agent 的 Tool 包装调用 / MCP Server 暴露 / REST 接口直接调用。

### 一次排障请求的链路

```
用户提问
  → Vue 前端（SSE 流式请求）
    → ChatController（JWT 校验 + 权限上下文传播 + latch 超时保护）
      → OpsAssistant（ReAct Agent，注入 System Prompt）
        → Tool（@RequiredPermission 鉴权 → 失败返回友好提示而非崩溃）
          → Skill（纯业务逻辑）
            → Mapper（DataPermissionInterceptor 按 tenant_id/dept_id 自动隔离）
              → MySQL
            → KnowledgeRetrievalTool → Milvus（RAG 检索）
      ← 结构化 SSE 事件（thinking / tool / observation / conclusion）回流前端渲染
```

---

## 权限模型（RBAC）

系统基于 `sys_permission` / `sys_role` / `sys_user_role` / `sys_role_permission` 四张表实现 RBAC，控制粒度到「工具」与「数据行」。

### 权限码（Permission Code）

| 权限码 | 含义 | 控制的工具 / 接口 |
|--------|------|------------------|
| `agent:chat` | AI 对话权限 | `ChatController` 对话入口（缺失则对话 403） |
| `alarm:read` | 告警查询 | `AlarmQueryTool` / 左侧告警列表接口 |
| `billing:read` | 账单查询 | `BillingQueryTool` |
| `resource:read` | 资源/负载/拓扑查询 | `ResourceLoadTool` / `ResourceRelationTool` |
| `rag:read` | 知识库（SOP）查询 | `KnowledgeRetrievalTool` |

### 默认角色权限矩阵

| 角色（role_code） | agent:chat | alarm:read | billing:read | resource:read | rag:read |
|-------------------|:---:|:---:|:---:|:---:|:---:|
| **admin**（SUPER_ADMIN） | ✅ | ✅ | ✅ | ✅ | ✅ |
| **ops_eng**（运维工程师） | ✅ | ✅ | ❌ | ✅ | ✅ |
| **ops_viewer**（运维只读） | ❌ | ❌ | ❌ | ✅ | ❌ |
| **finance**（财务人员） | ✅ | ❌ | ✅ | ❌ | ❌ |

> 角色与权限的绑定数据见 `cloud-ops-domain/src/main/resources/sql/init-permissions.sql`。

### 四层权限控制

1. **接口层**：`@PreAuthorize("hasAuthority('alarm:read')")` 等注解（如 `AlarmController`）
2. **工具层**：`@RequiredPermission("billing:read")` 注解 + `ToolPermissionChecker` 实现，权限不足时返回友好提示（不抛异常、不断流）
3. **数据层**：`DataPermissionInterceptor` 对非 `SUPER_ADMIN` 用户自动追加 `WHERE tenant_id = ? AND dept_id = ?`，实现行级隔离
4. **前端层**：依据登录用户权限动态渲染（如左侧告警列表区，无 `alarm:read` 则展示「无权限」占位且不发起请求）

### 跨线程上下文说明

Tool 常在 LangChain4j 异步线程执行，`SecurityContext`（ThreadLocal）会丢失。鉴权实现通过 `RequestContextHolder`（ChatController 在调 Agent 前写入当前 userId）+ `RequestContextStore` 做兜底查找，保证异步场景下的权限判定准确。

---

## 快速开始

### 环境要求

- JDK 21、Node.js 22+、MySQL 8（库名 `cloud_ops`）
- Docker（运行 Milvus 与可选的可观测性组件）
- API Key：`DEEPSEEK_API_KEY`（对话）、`DASHSCOPE_API_KEY`（向量）

### 1. 初始化数据库（按顺序执行三个 SQL）

```bash
# 进入项目根目录
cd cloud-ops-ai-assistant

# ① 业务 Mock 数据 + mock_alarm 表（含 tenant_id / dept_id 隔离字段）
mysql -u root -p cloud_ops < cloud-ops-domain/src/main/resources/sql/mock_data_init.sql

# ② RBAC 权限表与种子数据（sys_permission / sys_role / sys_user_role / sys_role_permission）
mysql -u root -p cloud_ops < cloud-ops-domain/src/main/resources/sql/init-permissions.sql

# ③ 对话记忆表（ChatMemory 持久化）
mysql -u root -p cloud_ops < cloud-ops-domain/src/main/resources/sql/chat_memory_ddl.sql
```

> ⚠️ **账号初始化提醒**：仓库未随附 `sys_user` 表的建表与种子脚本。首次部署需自行创建 `sys_user`
> （字段参考 `cloud-ops-security/.../entity/SysUser.java`，`password` 为 BCrypt 密文），并将账号 id 与角色
> 关联写入 `sys_user_role`（默认 admin 账号 id=1 绑定角色 id=1，详见 `init-permissions.sql` 注释）。
> 若 admin 的 id 非 1，请同步修改 `init-permissions.sql` 中的 `user_id` 值。

### 2. 配置环境变量

```bash
export DEEPSEEK_API_KEY="sk-xxxx"        # 对话模型
export DASHSCOPE_API_KEY="sk-xxxx"       # 向量模型
export MYSQL_PASSWORD="root"             # 数据库密码（MYSQL_USER 默认 root）
# JWT_SECRET 可选，未设置时使用配置中的兜底密钥
```

或复制 `.env` 并在启动命令中注入（CI / 容器场景）。

### 3. 启动后端（:8080）

```bash
mvn clean install -DskipTests
mvn -pl cloud-ops-app spring-boot:run
```

配置文件：`cloud-ops-app/src/main/resources/application.yml`
- 对话模型：`langchain4j.open-ai.chat-model.model-name`（默认 `deepseek-v4-pro`，可按需替换）
- 向量模型：`langchain4j.open-ai.embedding-model`（DashScope `text-embedding-v4`）
- Milvus：`milvus.host` / `milvus.port`（默认 `localhost:19530`）

### 4. 启动 Milvus（RAG 必需）

```bash
docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:latest milvus run standalone
```

首次使用需将 SOP 文档入库：

```bash
curl -X POST http://localhost:8080/api/rag/ingest
```

### 5. 启动前端（:3000）

```bash
cd cloud-ops-frontend
npm install
npm run dev
```

打开 http://localhost:3000 即可访问。

### 6. MCP Server（可选，独立进程）

```bash
mvn -pl cloud-ops-mcp package -DskipTests
java -jar cloud-ops-mcp/target/cloud-ops-mcp-0.0.1-SNAPSHOT.jar
```

Claude Desktop 配置（`claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "cloud-ops-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/cloud-ops-mcp/target/cloud-ops-mcp-0.0.1-SNAPSHOT.jar"]
    }
  }
}
```

---

## 验证

```bash
curl http://localhost:8080/health                  # 后端健康
curl -X POST http://localhost:8080/api/rag/ingest  # RAG 文档入库
curl http://localhost:8080/actuator/prometheus     # Prometheus 指标
open http://localhost:3000                         # 前端
open http://localhost:3001                         # Grafana 看板（匿名登录，需先启动监控组件）
```

---

## 前端功能

- **左侧实时告警列表**：依据 `alarm:read` 权限动态展示；无权限角色显示「无权限」占位且不发起请求；数据经后端按租户隔离
- **对话区 ReAct 可视化**：思考 / 工具调用 / 观察 / 结论 四色卡片，SSE 流式渲染
- **用户面板**：展示当前登录用户、角色与权限（中文名称）
- **排障报告下载**：排障类结论支持导出报告

> 前端所有请求默认相对路径 + JWT 鉴权，无需额外配置后端地址。

---

## 可观测性

- `management.endpoints.web.exposure.include: health,metrics,prometheus`
- ChatModel 调用延迟、token 消耗、工具调用次数等指标通过 Micrometer 导出至 Prometheus
- Grafana 看板（docker-compose 位于 `docker/` 目录，可选启用）

---

## 项目约定

- System Prompt 集中于 `cloud-ops-agent/src/main/resources/prompts/ops-assistant-system.txt`，
  定义了「排障场景模板」与「数据查询场景模板」两种输出格式及边界约束
- Tool 注册由 `tools-config.yml` 驱动，新增工具一般无需改 Controller
- 所有跨模块的权限注解与接口置于 `cloud-ops-common`，避免 `security` 与 `common` 循环依赖

---

## 常见问题

**Q：登录后左侧告警列表空白？**
A：检查账号是否拥有 `alarm:read` 权限；无权限时列表区会显示「无权限」占位。同时确认 `mock_alarm` 表已正确初始化且包含 `tenant_id` / `dept_id` 字段。

**Q：对话时所有工具都报「权限不足」？**
A：确认已执行 `init-permissions.sql`，且登录账号的 `sys_user.id` 与 `sys_user_role.user_id` 绑定正确（admin 默认 id=1）。

**Q：SSE 对话卡住不返回？**
A：`ChatController` 已对 `CountDownLatch` 设置 120 秒超时保护；若仍卡住，检查大模型 API Key 是否有效、Milvus 是否可达。

**Q：查询返回空但数据库中确有数据？**
A：非 `SUPER_ADMIN` 角色受 `DataPermissionInterceptor` 租户/部门隔离限制，请确认登录账号的 `tenant_id` / `dept_id` 与数据匹配。
