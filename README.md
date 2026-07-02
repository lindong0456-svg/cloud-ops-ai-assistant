# cloud-ops-ai-assistant

云运维智能助手 — Java AI Agent 项目（LangChain4j + RAG + MCP + ReAct）

## 技术栈

- Spring Boot 3.4 + Java 21
- LangChain4j 1.0.0-beta3（ReAct Agent + Tool + RAG）
- DeepSeek API（OpenAI 兼容）
- Milvus（向量库）
- MySQL + MyBatis-Plus（Mock 业务数据）
- Vue 3 + Vite（前端）

## 项目结构（Maven 多模块 - 6 模块分层）

```
cloud-ops-parent/                  ← 父 POM，统一依赖版本管理
├── pom.xml
├── cloud-ops-common/              ← 公共层：AbstractTool、ToolResult（瘦身后）
├── cloud-ops-domain/              ← 领域层：实体、Mapper、Mock SQL
├── cloud-ops-rag/                 ← 能力层：RAG 文档入库 + 向量检索
├── cloud-ops-skill/               ← 能力层：原子能力内核（无 AI 感知，可多方复用）
├── cloud-ops-agent/               ← 协议层：Tool 包装 Skill（@Tool 适配 Agent）
└── cloud-ops-app/                 ← 启动层：Spring Boot 启动类 + Controller
```

### 模块依赖关系（严格自上而下，无环）

```
        app
       / | \ \
      /  |  \ \
   agent rag skill domain
     |       |    |
     |───────┘    |
     |            |
   common ←──────┘
```

- `common`：AbstractTool + ToolResult，唯一被全员依赖，保持瘦身
- `domain`：实体 + Mapper + SQL，无内部依赖
- `rag`：RAG 能力，依赖 common（KnowledgeRetrievalTool extends AbstractTool）
- `skill`：原子能力内核，依赖 domain（用 Mapper），无 AI 感知
- `agent`：Tool 包装 Skill，依赖 common + skill
- `app`：聚合所有模块，唯一可执行入口

### Skill 与 Tool 的关系（核心设计）

```
┌─────────────────────────────────────────┐
│  Tool（@Tool 注解，AI 可调用）           │
│  ┌───────────────────────────────────┐  │
│  │  Skill（纯业务逻辑，无 AI 感知）   │  │
│  │  - 无状态                          │  │
│  │  - 无 @Tool 注解                   │  │
│  │  - 可被 Tool / MCP / REST 复用     │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

同一个 `AlarmSearchSkill` 可被：Agent 的 Tool 包装调用 / MCP Server 暴露 / REST 接口直接调。

## 快速启动

```bash
# 1. 复制环境变量配置，填入你的 DeepSeek / 百炼 API Key
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY 和 DASHSCOPE_API_KEY

# 2. 启动 MySQL（本地或 Docker）
docker run -d --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=cloud_ops mysql:8

# 3. 初始化 Mock 数据（在 Navicat 或命令行执行）
# 执行 cloud-ops-domain/src/main/resources/sql/mock_data_init.sql

# 4. 启动 Milvus
docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 milvusdb/milvus:latest milvus run standalone

# 5. 编译整个项目
mvn clean compile

# 6. 运行项目
mvn -pl cloud-ops-app spring-boot:run

# 7. 验证
curl http://localhost:8080/health

# 8. RAG 文档入库（首次启动后执行一次）
curl http://localhost:8080/api/rag/ingest
```

## 核心能力

- ReAct Agent 自主排障（告警→查资源→查负载→查SOP→给方案）
- 4 个运维 Tool：告警/拓扑/负载/账单（底层4个Skill复用）
- RAG 检索：向量+关键词+混合+Rerank（待实现）
- MCP Server 标准化暴露运维能力（待实现）
- InputGuardrail 护轨拦截敏感操作（待实现）
- 配置驱动 Tool 注册（待实现）

## 开发进度

- [x] 阶段1：数据层（Mock 数据 4 张表）
- [x] 阶段2：Tool 层（4 个 Skill + 4 个 Tool 包装）
- [x] 阶段3a：RAG 文档入库
- [ ] 阶段3b：RAG 检索服务（向量+关键词+混合+Rerank）
- [ ] 阶段4：Agent 核心层（OpsAssistant + Memory + Guardrail）
- [ ] 阶段5：API 层（SSE 流式）
- [ ] 阶段6：MCP Server（新建 cloud-ops-mcp 模块）
- [ ] 阶段7：Vue3 前端
- [ ] 阶段8：Docker Compose 部署

## 模块演进路线

当前 6 模块架构已为后续演进预留空间：

| 阶段 | 动作 | 触发时机 |
|------|------|----------|
| 阶段6 | 新建 `cloud-ops-mcp` 模块 | 接入 MCP Server 前 |
| 未来 | skill 模块持续扩充原子能力 | 业务迭代时 |
