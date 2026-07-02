# cloud-ops-ai-assistant

云运维智能助手 — Java AI Agent 项目（LangChain4j + RAG + MCP + ReAct）

## 技术栈

- Spring Boot 3.4 + Java 21
- LangChain4j 1.0（ReAct Agent + Tool + RAG）
- DeepSeek API（OpenAI 兼容）
- Milvus（向量库）
- MySQL + MyBatis-Plus（Mock 业务数据）
- Vue 3 + Vite（前端）

## 快速启动

```bash
# 1. 复制环境变量配置，填入你的 DeepSeek API Key
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY

# 2. 启动 MySQL（本地或 Docker）
docker run -d --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=cloud_ops mysql:8

# 3. 启动 Milvus
docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 milvusdb/milvus:latest milvus run standalone

# 4. 运行项目
mvn spring-boot:run

# 5. 验证
curl http://localhost:8080/health
```

## 核心能力

- ReAct Agent 自主排障（告警→查资源→查负载→查SOP→给方案）
- 5 个 Tool：告警/拓扑/负载/账单/RAG检索
- MCP Server 标准化暴露运维能力
- InputGuardrail 护轨拦截敏感操作
- 配置驱动 Tool 注册
