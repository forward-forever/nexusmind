# NexusMind Project Overview

## 项目背景

NexusMind 是一个个人 Java AI engineering project。它的目标不是模拟不存在的商业流量或客户需求，而是系统性实践一条完整技术演进路线：从文档到 grounded answer 的 Basic RAG，进入可评价的 Retrieval Quality，再进入 model-driven Agent，最后补齐 durability、resilience、concurrency、context control、MCP 与 observability 等工程边界。

项目最终同时保留 Fixed RAG 与 Agent：前者强调路径稳定和可预测性，后者强调模型基于中间结果决定下一步动作。两条路径共享知识、检索和模型基础设施，但具有不同的 orchestration 语义。

## 项目目标

NexusMind 主要回答以下工程问题：

1. 文档如何从上传、解析、分块走到可检索状态？
2. Retrieval 如何被独立评价，而不是只观察最终回答？
3. Dense、BM25、RRF、Rerank 的职责和取舍是什么？
4. Agent 如何执行真实 Function Calling，同时限制工具参数、调用次数和总时长？
5. 多个 HTTP Request 如何形成 Conversation，又不复用错误的 Run 状态和 Source ID？
6. JVM crash、Provider 短暂故障、并发 Session、上下文增长如何处理？
7. 外部 MCP Tool 如何在可控信任边界内接入？
8. 系统如何提供低基数、无敏感内容的可观测性 surface？

## 核心能力

### Knowledge and Ingestion

支持 PDF、Markdown、UTF-8 TXT。Upload、Process、Index 是明确分离的动作：Process 负责解析和 Chunk 持久化，Index 负责 Embedding 和 Milvus Projection。MySQL `knowledge_document` / `knowledge_chunk` 是业务事实，Milvus 可由它们重建。

### Retrieval Quality

Retrieval 通过统一 `RetrievalService` 暴露四种实现：

- `DENSE`：`qwen3.7-text-embedding-flash` + Milvus HNSW/COSINE。
- `BM25`：Raw Query + Chinese analyzer + Milvus sparse retrieval。
- `HYBRID_RRF`：并行 Dense/BM25，分别做 MySQL visibility validation 后按 rank 融合。
- `HYBRID_RERANK`：Hybrid Candidate TopN 交给 `qwen3.7-text-rerank`，再取 Final TopK。

Golden Dataset、Page-to-Chunk Resolver、HitRate/Recall/MRR/Latency Report 和 Retrieval Lab 让 Retrieval 成为可独立验证的层。

### RAG

Fixed RAG 使用配置选择 Retriever，构建 token-aware rank-prefix Context，并通过 SSE 返回回答。Source 列表只包含真正进入模型 Context 的 Chunk，避免 UI 展示模型没有见过的证据。

### Agent

Agent 使用 `qwen3.5-flash` 的真实 Function Calling。应用控制 Tool Loop 和安全边界，模型决定下一步：可以直接回答，可以调用 `search_knowledge_base`，也可以根据搜索结果继续调用 `get_document_context(sourceId)`。当前 Run 的 Tool Trace 不进入跨请求 Memory。

### Controlled MCP

外部 Tool 通过 Spring AI Streamable HTTP MCP Client 接入。启动发现不等于授权；只有经过本地命名规则和 allowlist 的 Tool 才进入 Agent Catalog。外部 schema、description 和 result 都属于不可信数据，并受 Tool Limit、deadline 和 Tool Result Budget 约束。

## 技术架构

系统由 Vue 3 Workbench 与 Spring Boot 单体后端组成。Backend 内以 feature-first 组织 Knowledge、Retrieval/RAG、Agent、MCP、Context、Resilience 和 Observability。MySQL 承担业务状态、Durable Task 和 Conversation Memory；Milvus 承担 Dense/BM25 Retrieval Projection；Model Studio 提供 Chat、Embedding、Rerank。

完整 Pipeline、Mermaid 图、数据所有权与故障模型见 [architecture.md](architecture.md)。

## V1 → V4 演进

### V1 Basic RAG：建立 grounded answer 闭环

V1 解决的是“系统能否把用户文档变成有来源的流式回答”。它建立 Document Parser、Chunk、Embedding、Milvus Dense Retrieval、RAG Context、Citation、SSE 与 Vue 页面。此时重点是端到端闭环，而不是算法比较或 Agent 行为。

### V2 Retrieval Quality：从能检索进入可评价

V2 解决的是“怎么知道 Retrieval 是否有效，以及不同机制是否互补”。项目先建立 Golden Dataset、HitRate/Recall/MRR/Latency Evaluation，再增加 BM25、Application RRF 和 Cross-Encoder Rerank。所有策略通过同一 Registry 和 Evaluation Engine 比较，避免为某个算法定制指标。

### V3 Agent：从固定 Pipeline 进入模型驱动决策

V3 解决的是“模型能否根据中间结果选择下一步动作”。Agent 可以 direct answer、search only 或 Search → Context；后者来自第二次模型 `tool_call`，不是 Java 固定 Workflow。随后加入 Conversation Memory、run-scoped Source、Tool Trace、Session UI 和客观的 Behavior Evaluation。

### V4 Production Engineering：明确故障和资源边界

V4 解决的是“当系统不再只运行 happy path 时会发生什么”。它引入 Durable Async Tasks、Provider bounded retry、Parallel Hybrid、Session DB Lease、Application Token Budget、Controlled MCP Client 和 Micrometer/Prometheus。V4 结束后项目功能冻结。

## Key Engineering Decisions

| Decision | Why | Alternative | Trade-off |
|---|---|---|---|
| MySQL Source of Truth / Milvus Projection | 业务状态需要事务事实，索引可以重建 | 让 Milvus 同时承担业务事实 | Retrieval 必须回查 visibility |
| Application RRF | 各 route 先过滤 stale candidate，并保留 provenance | Milvus native hybrid | 应用层 orchestration 更多 |
| 默认 RAG 使用 Dense | 当前 benchmark 中 Dense 已达到 ceiling | 默认 Hybrid/Rerank | 放弃未经证据支持的额外 latency/cost |
| Application-controlled Tool Loop | 统一 SSE、Tool Limit、deadline 与 error contract | Framework automatic execution | 应用代码需要维护 loop state |
| Run-scoped Source IDs | 模型不接触内部 Chunk/Document ID | 直接暴露数据库 ID | 旧 `[S1]` 不能跨 Run 复用 |
| 只保存 User + Final Assistant | Memory 表达 conversation outcome | 持久化完整 Tool Trace | 新 Run 需要时重新调用工具 |
| MySQL Durable Task | 单体已依赖 MySQL，足以实现持久接收、claim、恢复 | Kafka/RabbitMQ/Workflow Engine | 不是独立分布式任务平台 |
| DB Lease | 多实例共同可见，不保持长事务锁 | JVM Lock / `SELECT FOR UPDATE` 全程持有 | Crash 后需等待 lease expiry |
| Application Token Budget | 控制 latency、cost 和 Tool Loop 增长 | 直接使用 Provider 最大窗口 | 本地 estimator 对 Qwen 只是近似 |
| Controlled Remote MCP | 启动发现、命名、allowlist 后才暴露 | 用户任意注册 MCP | 不支持 OAuth、热更新或任意服务器 |

## Evaluation

### Retrieval Evaluation

30-query、22-page synthetic technical corpus 使用 HitRate@K、Recall@K、MRR@K 与 latency 比较四种 Retriever。DENSE、BM25、HYBRID_RRF、HYBRID_RERANK 的 HitRate@1 与 MRR@1 都为 1.0，表明这个小型 Dataset 已饱和。

正确结论不是“高级算法一定更好”，而是：四条 Pipeline 正常、统一评估架构可复用、当前 Dataset 提供 regression safety；它无法测出 Hybrid/Rerank 的额外质量增益。因此默认 Retriever 仍为 Dense。完整数字见 [v2-retrieval-quality.md](v2-retrieval-quality.md)。

### Agent Behavior Evaluation

11 个 Scenario 覆盖 DIRECT、SEARCH、MULTI_TOOL、INSUFFICIENT_KNOWLEDGE、MEMORY、GUARDRAIL。指标关注 Completion、Expected Tool Sequence、Unexpected Tool Calls、Tool/Model Turn 数、Duration 与 Session Continuity。

它不使用 LLM-as-a-Judge，也不把回答风格或事实质量包装成确定性 100%。真实模型 Evaluation 只有在用户显式运行时才会产生调用和费用。

## Production Engineering

### Durability

HTTP 先把 Process/Index Task 提交到 MySQL，再由 Worker 执行。Logical identity、atomic claim、heartbeat、`runToken` fencing、stale recovery 和 business-state reconciliation 共同实现 at-least-once 下的最终收敛。

### Resilience

网络错误、timeout、408、429、5xx 使用 bounded retry；400/401/403/404 和非法配置立即失败。Embedding 不切换模型，避免污染向量空间。Streaming 已产生可观察输出或已执行 Tool 后不重放。

### Concurrency

Dense/BM25 在有界专用 Executor 中并行。Agent Session 使用 DB-time lease 保证一个 Session 同时只有一个 active Run；`runId` 对 release 与 Memory Commit 做 fencing。

### Context Control

RAG Context、历史 Memory、单次 Tool Result、整个 Run Tool Result 和每次 Model Turn 都受应用 token budget 约束。Memory 以完整 Turn 淘汰；当前 User 和 System Policy 不被静默截断。

### Observability

Actuator 提供 health/info/prometheus。`nexusmind.*` metrics 覆盖 RAG、Retrieval、Provider、Agent、Tool、MCP、Document Task、Context Budget、Session 与 Citation；Tag 只使用低基数枚举。日志不记录 prompt、正文、Tool Result、Authorization、API key 或 MCP URL。

## Known Limitations

- Qwen token count 使用本地近似 estimator。
- 没有 Provider circuit breaker 或 alternate-provider fallback。
- MCP 仅支持受控 Streamable HTTP Tools，无 OAuth、Resources、Prompts 或热更新。
- Cancellation 是 best effort。
- Durable Task 是 MySQL queue，不是独立分布式 MQ。
- Conversation Memory 没有长期 semantic memory。
- 提供 metrics surface，但不内置 Grafana、OpenTelemetry 或集中式日志平台。
- 项目不支持 multi-agent。

这些是冻结版本的边界，不是新的功能 Roadmap。
