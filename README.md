# NexusMind

**Production-Oriented Java AI Knowledge & Agent Platform**

NexusMind 是一个面向工程实践的私有知识库、检索增强生成与 Agent 平台。项目保留固定 RAG 与 Agent 两条独立路径，重点展示检索质量、可控工具调用、持久化任务、并发与上下文治理，以及可观测性边界；它不宣称已经具备完整生产基础设施。

## Features

- PDF、Markdown、UTF-8 TXT 上传、解析、分块与引用
- MySQL 业务事实源与 Milvus 派生检索索引
- DENSE、BM25、Application RRF、Cross-Encoder Rerank
- Golden Dataset、HitRate / Recall / MRR 与 Retrieval Lab
- SSE Streaming RAG 与可配置 Retriever
- qwen3.5-flash 原生 Function Calling 与用户控制 Tool Loop
- Knowledge Search、Document Context 与受控 Remote MCP Tools
- Multi-step Tool Chaining、Conversation Memory 与 Session Lease
- MySQL Durable Document Tasks、Crash Recovery 与幂等 Process / Index
- Provider bounded retry、parallel hybrid retrieval 与 token budgets
- Actuator Health、Micrometer / Prometheus metrics 与低基数标签策略
- Vue 3 Tabbed Workbench：Knowledge、Agent Chat、RAG Chat、Retrieval Lab

## Architecture

```text
Vue Workbench
      │
Spring Boot API / SSE
      │
      ├── Knowledge ── Durable Tasks ── Parse / Chunk ── MySQL
      │                                                   │
      │                                                   └── Milvus projection
      ├── Retrieval ── Dense / BM25 ── RRF ── Rerank
      ├── RAG ── Retrieval ── Token-budgeted Context ── ChatModel
      └── Agent ── Tool Calling Loop
                    ├── Native Knowledge Tools
                    ├── Allowlisted Streamable HTTP MCP Tools
                    ├── Conversation Memory / Session Lease
                    └── Token Budget / Guardrails
```

MySQL 是业务 Source of Truth；Milvus 是可重建的检索投影；Document Task 只协调执行；Agent Session 保存跨请求对话，Agent Run 保存单次请求状态。完整边界、数据所有权与失败模型见 [docs/architecture.md](docs/architecture.md)。

当前普通 RAG 默认仍使用 `DENSE`。BM25、Hybrid RRF 与 Hybrid Rerank 可通过配置、Search API 和 Retrieval Lab 显式选择。

## Workbench

单页 Vue Workbench 使用四个保留状态的主 Tab：

- **Knowledge**：Knowledge Base、文档上传、异步 Process / Index 与任务状态
- **Agent Chat**：Streaming、多轮 Session、Tool Trace、Sources 与 MCP Tool 行为
- **RAG Chat**：固定 Retrieval → Context → Chat 路径
- **Retrieval Lab**：四种 Retriever 的单 Query 结果、贡献与 provenance 对比

## Demo Scenarios

1. 上传文档，观察 Process / Index 从 `PENDING` 到 `RUNNING`、`SUCCEEDED`。
2. 在 Retrieval Lab 比较 DENSE、BM25、HYBRID_RRF、HYBRID_RERANK。
3. Agent 先执行 `search_knowledge_base`，再按需执行 `get_document_context(S1)`。
4. 配置可信 Streamable HTTP MCP Server，展示模型选择 allowlisted external tool。

## Local Configuration

复制占位模板并只在未跟踪的本地文件中填写凭据：

```bash
cp deploy/.env.example deploy/.env
```

主要环境变量名：

```text
DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD
DASHSCOPE_API_KEY
CHAT_BASE_URL EMBEDDING_BASE_URL RERANK_BASE_URL
MILVUS_URI
NEXUSMIND_MCP_ENABLED MCP_DEMO_BASE_URL MCP_DEMO_ENDPOINT MCP_ALLOWED_TOOLS
```

不要提交 `deploy/.env`。MCP remote URL、API key、Authorization header 与模型输入内容不应进入版本库或日志。

## Observability

默认仅暴露：

```text
/actuator/health
/actuator/info
/actuator/prometheus
```

所有应用指标使用 `nexusmind.*` 前缀，且不使用 runId、sessionId、documentId、query 或动态 MCP tool name 作为标签。指标清单与 Prometheus 示例见 [docs/observability.md](docs/observability.md)。

## Technology

- Java 17, Spring Boot 4.1.0, Spring AI 2.0.1
- Spring MVC, MyBatis 4.1.0, Flyway, MySQL 8
- Milvus 2.6, Alibaba Cloud Model Studio
- Vue 3, TypeScript, Vite, Vitest

## Delivery Status

- V1 Basic RAG ✅
- V2 Retrieval Quality ✅
- V3 Agent ✅
- V4 Production Engineering ✅

V4 完成了 Durable Async Tasks、Provider Resilience、Parallel Hybrid、Agent Session Concurrency、Token / Context Management、Controlled MCP Client 与 Observability。项目在 V4 冻结，不继续规划新功能版本。

## Documentation

- [Architecture](docs/architecture.md)
- [Observability](docs/observability.md)
- [V1 Demo](docs/v1-demo.md)
- [V2 Retrieval Quality](docs/v2-retrieval-quality.md)
- [V3 Agent](docs/v3-agent.md)
- [Async Document Tasks](docs/async-document-tasks.md)
- [Resilience and Concurrency](docs/resilience-concurrency.md)
- [Token and Context Management](docs/token-context-management.md)
- [Controlled MCP Client](docs/mcp-client-integration.md)
