# cloud-ops-ai-assistant

云运维智能助手 — Java AI Agent 项目（LangChain4j + RAG + MCP + ReAct）

## 技术栈

- Spring Boot 3.4 + Java 21
- LangChain4j 1.0.0-beta3（ReAct Agent + Tool + RAG）
- DeepSeek API（OpenAI 兼容）
- Milvus（向量库）
- MySQL + MyBatis-Plus（Mock 业务数据）
- Vue 3 + Vite（前端）
- Prometheus + Grafana（可观测性）
- MCP 官方 SDK 0.14.0（独立进程）

## 项目结构（Maven 多模块 - 7 模块分层）

```
cloud-ops-parent/                  ← 父 POM（统一版本管理）
├── pom.xml
├── cloud-ops-common/              ← 公共层：AbstractTool、ToolResult
├── cloud-ops-domain/              ← 领域层：Entity、Mapper、SQL
├── cloud-ops-rag/                 ← 能力层：RAG 入库 + 混合检索 + Rerank 精排
├── cloud-ops-skill/               ← 能力层：纯 POJO Skill（构造器注入，零框架依赖）
├── cloud-ops-agent/               ← 协议层：@Tool 包装 Skill + Guardrail + Memory
├── cloud-ops-app/                 ← 启动层：Spring Boot + Controller（主入口）
└── cloud-ops-mcp/                 ← MCP Server：独立进程 stdio 传输（官方 SDK）
```

### 模块依赖关系（严格自上而下，无环）

```
        app
       / | \ \
      /  |  \ \
   agent rag skill domain
     |       |    |\____
     |───────┘    |     \
     |            |      \
   common ←──────┘       mcp(独立进程)
```

- `common`：AbstractTool + ToolResult，唯一被全员依赖，保持瘦身
- `domain`：实体 + Mapper + SQL，无内部依赖
- `rag`：RAG 能力，依赖 common
- `skill`：纯 POJO 原子能力，构造器注入，零框架依赖。可被 @Tool / MCP / REST 复用
- `agent`：Tool 包装 Skill，依赖 common + skill
- `app`：聚合所有模块，唯一可执行入口（Spring Boot）
- `mcp`：MCP Server 独立进程，依赖 domain + skill

### Skill 与 Tool 的关系（核心设计）

```
       @Tool(Agent 进程内)     MCP(跨进程 stdio)    REST(前端 HTTP)
              ↓                    ↓                   ↓
       ┌─────────────────────────────────────────────────┐
       │   Skill（纯 POJO，构造器注入，零框架依赖）       │
       │   - 无 @Tool 注解                               │
       │   - 无 Spring 依赖（MCP 端直接 new）             │
       └─────────────────────────────────────────────────┘
```

同一个 `AlarmSearchSkill` 可被：Agent 的 Tool 包装调用 / MCP Server 暴露 / REST 接口直接调。

## 核心能力

- **ReAct Agent 自主排障**：告警→查资源→查负载→查SOP→综合分析，平均 3-4 次工具调用
- **RAG 混合检索**：向量(Milvus HNSW) + 关键词 + RRF 粗排 + gte-rerank 精排，两阶段召回
- **MCP Server**：官方 SDK 独立进程 stdio 传输，可被 Claude Desktop 或任意 MCP 客户端调用
- **全链路可观测性**：ChatModelListener + Micrometer + Prometheus + Grafana 看板
- **traceId 链路追踪**：贯穿 chat→agent→tool→rag→llm，可定位每一步延迟
- **ChatMemory MySQL 持久化**：MessageWindowChatMemory 滑动窗口 20 轮 + MySQL 二次兜底
- **InputGuardrail 输入护轨**：三层过滤（关键词+白名单），拦截敏感操作
- **Self-Reflection 自动重试**：工具返回空/异常时自动换关键词重试（prompt 工程）
- **SSE 流式对话**：TokenStream + Flux.create() + 结构化事件，排障过程透明化
- **前端混合渲染**：ReAct 四色卡片 + 工具进度 + 报告下载 + 对话统计面板
- **配置驱动 Tool 注册**：YAML 配置增减 Tool，不改代码
- **4 个运维 Tool**：告警/拓扑/负载/账单，底层 4 个 Skill 复用

## 开发进度

- [x] 阶段1：数据层（Mock 数据 4 张表）
- [x] 阶段2：Tool 层（4 个 Skill + 4 个 Tool 包装）
- [x] 阶段3：RAG 文档入库 + 混合检索（向量+关键词+RRF+Rerank）
- [x] 阶段4：Agent 核心（OpsAssistant + ChatMemory + Guardrail + Self-Reflection）
- [x] 阶段5：API 层（SSE 流式 + 结构化事件 + 统计面板）
- [x] 阶段6：MCP Server（cloud-ops-mcp 模块，官方 SDK）
- [x] 阶段7：Vue3 前端（完整 UI：告警列表·对话·ReAct·报告·统计）
- [x] 阶段8：可观测性（Micrometer + Prometheus + Grafana 看板）

## 快速启动

### 前置依赖

```bash
# 1. 环境变量
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY 和 DASHSCOPE_API_KEY

# 2. MySQL（业务库）
docker run -d --name mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=cloud_ops mysql:8

# 3. Milvus（向量库，standalone 模式）
docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:latest milvus run standalone

# 4. 初始化 Mock 数据（执行 SQL 文件）
# cloud-ops-domain/src/main/resources/sql/mock_data_init.sql

# 5. Prometheus + Grafana（可观测性看板，可选）
docker compose -f docker/docker-compose-monitoring.yml up -d
```

### 启动应用

```bash
# 编译
mvn clean install -DskipTests

# 后端（:8080）
mvn -pl cloud-ops-app spring-boot:run

# 前端（:3000）
cd cloud-ops-frontend && pnpm install && pnpm dev

# MCP Server（独立进程，可选）
mvn -pl cloud-ops-mcp package -DskipTests
java -jar cloud-ops-mcp/target/cloud-ops-mcp-0.0.1-SNAPSHOT.jar
```

### 验证

```bash
curl http://localhost:8080/health                 # 后端健康
curl http://localhost:8080/api/rag/ingest         # RAG 文档入库
curl http://localhost:8080/actuator/prometheus    # Prometheus 指标
open http://localhost:3000                        # 前端
open http://localhost:3001                        # Grafana 看板（匿名登录）
```

### MCP Server 验证（Claude Desktop）

```json
// claude_desktop_config.json
{
  "mcpServers": {
    "cloud-ops-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/cloud-ops-mcp/target/cloud-ops-mcp-0.0.1-SNAPSHOT.jar"]
    }
  }
}
```
