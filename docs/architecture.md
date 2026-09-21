# NexusMind Architecture（NexusMind 架构）

## System overview（系统概览）

```text
                         ┌────────────────┐
                         │ Vue Workbench  │
                         └───────┬────────┘
                                 │ HTTP / SSE
                          Spring Boot
                                 │
      ┌─────────────┬────────────┼─────────────┐
      │             │            │             │
   Knowledge     Retrieval      RAG          Agent
      │             │            │             │
      │      Dense / BM25 / RRF  │       Tool Calling Loop
      │            / Rerank      │        ┌────┴────┐
      │             │            │      Native     MCP
      ▼             ▼            │       Tools     Client
    MySQL          Milvus      Retrieval            │
      ▲                                             Remote
      │                                             MCP
 Durable Tasks
```

The code is organized feature-first and layer-second. API, application logic, domain vocabulary, and persistence details stay inside their owning feature. NexusMind deliberately avoids a global `controller/service/mapper/domain` package tree and does not add artificial DDD abstractions where a typed configuration, enum, MyBatis entity, mapper, and application service are sufficient.

代码按「功能优先、分层其次」的方式组织。API、应用逻辑、领域词汇和持久化细节都保留在各自所属的功能模块内部。NexusMind 刻意避免全局的 `controller/service/mapper/domain` 包结构，在类型化配置、枚举、MyBatis 实体、mapper 和应用服务已经足够的地方，不会添加人为的 DDD 抽象。

## Ingestion and durable work（数据摄入与持久化任务）

```text
Upload
  ↓
knowledge_document (UPLOADED)
  ↓ enqueue
knowledge_document_task (PENDING)
  ↓ atomic claim + runToken fencing
Worker (RUNNING + heartbeat)
  ├── PROCESS → parser → chunks in MySQL → READY
  └── INDEX   → embedding → Milvus upsert → INDEXED
  ↓
SUCCEEDED / FAILED
```

The MySQL task row is a durable queue and execution coordination record. Execution is at-least-once. Stable chunk identity, transactional chunk replacement, Milvus upsert, stale-heartbeat recovery, and business-state reconciliation make repeated work converge; this is not described as exactly-once execution.

MySQL 任务行既是持久化队列，也是执行协调记录。执行语义为至少一次。稳定的 chunk 标识、事务化的 chunk 替换、Milvus upsert、过期心跳恢复以及业务状态对账，使重复执行最终收敛；这并未被描述为恰好一次执行。

## Retrieval and RAG（检索与 RAG）

Dense retrieval provides semantic matching, while BM25 provides lexical matching. Each route applies MySQL visibility validation before Application RRF. Hybrid executes Dense and BM25 concurrently but preserves the same candidate depth, RRF formula, deterministic tie-break, failure behavior, and provenance. Hybrid Rerank sends the RRF TopN to `qwen3.7-text-rerank` and maps results by provider index.

Dense 检索提供语义匹配，BM25 提供词法匹配。每条路由都会在应用层 RRF 之前执行 MySQL 可见性校验。Hybrid 并发执行 Dense 和 BM25，但保持相同的候选深度、RRF 公式、确定性平局裁决、失败行为和来源信息。Hybrid Rerank 把 RRF 的 TopN 发送给 `qwen3.7-text-rerank`，并按提供商返回的索引映射结果。

`RetrievalResult.model` and `dimension` were reviewed during final cleanup. They remain as optional execution metadata because evaluation reports and the retrieval debug response use them for reproducibility; non-vector routes may leave them absent/zero. Removing them would create broad type churn without changing behavior, so this P2 was documented rather than expanded into a retrieval-model redesign.

在最终清理中重新审视了 `RetrievalResult.model` 和 `dimension`。它们作为可选的执行元数据保留下来，因为评估报告和检索调试响应会用它们来保证可复现性；非向量路由可以让它们缺失或为零。移除它们会造成大范围的类型改动却不会改变行为，因此这个 P2 项只是被记录在文档中，而没有扩展成一次检索模型的重构。

Fixed RAG is intentionally predictable:

固定 RAG 刻意保持可预测：

```text
Question → configured RetrievalService → token-budgeted rank prefix
         → Prompt → ChatModel → SSE answer + only the sources the model saw
```

The product default remains DENSE because the current benchmark is saturated and provides no evidence that more latency and provider cost should be enabled by default.

产品默认仍然是 DENSE，因为当前的基准测试已经饱和，没有证据表明应该默认开启更高的延迟和提供商成本。

## Agent（Agent）

```text
                    ┌→ search_knowledge_base → Retrieval V2
Agent LLM → tool ───┤
                    ├→ get_document_context → MySQL chunks
                    └→ allowlisted MCP tool → Remote MCP server
      ↑                                      │
      └──────────── tool result ─────────────┘
```

The Java layer does not hard-code Search → Context. Each next action comes from a real model `tool_call`. Native tools obtain `knowledgeBaseId`, the deadline, source registry, and run budget from server-side ToolContext. MCP is disabled by default, Streamable HTTP only, startup-discovered, allowlisted, name-validated, and treated as an external untrusted capability.

Java 层不会硬编码 Search → Context 的流程。每一个后续动作都来自模型真实的 `tool_call`。原生工具从服务端的 ToolContext 获取 `knowledgeBaseId`、截止时间、来源注册表和 run 预算。MCP 默认禁用，仅支持 Streamable HTTP，在启动时被发现，经过白名单校验和名称校验，并被当作外部的不可信能力对待。

Cross-request memory persists only `USER` and final `ASSISTANT` messages. Current-run assistant tool calls and tool responses remain execution state. A DB-time session lease allows one active run per session; `runId` fences release and final memory commit. Source IDs such as `S1` are scoped to one Agent Run, and historical source markers are sanitized before reuse.

跨请求记忆只持久化 `USER` 和最终的 `ASSISTANT` 消息。当前 run 中 assistant 的工具调用和工具响应仍属于执行状态。基于数据库时间的会话租约允许每个会话只有一个活跃 run；`runId` 为释放操作和最终记忆提交提供栅栏保护。诸如 `S1` 这样的来源 ID 只作用于单个 Agent Run，历史来源标记在重用之前会被清洗。

Application token budgets constrain RAG context, historical memory, each tool result, total run tool results, and every model turn. Estimates are conservative local approximations, not exact Qwen token counts.

应用层 token 预算约束 RAG 上下文、历史记忆、每个工具结果、整个 run 的工具结果总量以及每一次模型轮次。估算值是保守的本地近似值，而不是 Qwen 的精确 token 计数。

## Data ownership（数据归属）

| Component | Ownership |
|---|---|
| MySQL `knowledge_*` | Business source of truth for KBs, documents and chunks |
| Milvus | Derived, rebuildable retrieval projection |
| `knowledge_document_task` | Durable execution coordination, not business truth |
| Agent Session / Message | Cross-request conversational outcome |
| AgentRunContext | Single-run counters, deadline, source identity and tool budget |
| MCP Server | External, untrusted capability governed by local policy |

| 组件 | 归属 |
|---|---|
| MySQL `knowledge_*` | 知识库、文档和 chunk 的业务真相来源 |
| Milvus | 派生的、可重建的检索投影 |
| `knowledge_document_task` | 持久化执行协调记录，而非业务真相 |
| Agent Session / Message | 跨请求的对话结果 |
| AgentRunContext | 单次 run 的计数器、截止时间、来源标识和工具预算 |
| MCP Server | 外部的不可信能力，由本地策略管控 |

## Failure model（故障模型）

- Provider transient failures: bounded retry with backoff and jitter.
  - 提供商瞬时故障：带退避和抖动的有界重试。
- Provider permanent failures: immediate failure; no alternate-model fallback.
  - 提供商永久性故障：立即失败；没有备用模型回退。
- Streaming failure after observable output or tool execution: fail without replay.
  - 在产生可观察输出或执行工具之后发生流式失败：直接失败，不重放。
- Hybrid route failure: whole Hybrid request fails; an empty route is still valid.
  - Hybrid 路由失败：整个 Hybrid 请求失败；某条路由为空仍然是合法的。
- Document worker crash: stale heartbeat recovery plus business-state reconciliation.
  - 文档 worker 崩溃：过期心跳恢复加上业务状态对账。
- Agent session contention: atomic DB lease; crash recovery through expiry.
  - Agent 会话竞争：原子性的数据库租约；通过过期机制实现崩溃恢复。
- Context overflow: deterministic application budget error before provider invocation.
  - 上下文溢出：在调用提供商之前返回确定性的应用层预算错误。
- MCP failure: tool error; no MCP-specific automatic retry.
  - MCP 失败：工具错误；没有 MCP 专属的自动重试。

## Guardrails and observability（护栏与可观测性）

Agent limits include five tool invocations, one absolute deadline, minimal schemas, run-scoped source IDs, session/KB binding, token budgets, and untrusted tool-result rules. Actuator exposes health, info and Prometheus only. Micrometer metrics use the `nexusmind.*` namespace and low-cardinality tags. Logs carry execution identity and outcome but exclude prompts, document contents, tool result bodies, authorization values, API keys, and remote MCP URLs.

Agent 的限制包括五次工具调用、一个绝对截止时间、最小化的 schema、run 级别的来源 ID、会话/知识库绑定、token 预算以及不可信工具结果规则。Actuator 只暴露 health、info 和 Prometheus。Micrometer 指标使用 `nexusmind.*` 命名空间和低基数标签。日志携带执行标识和结果，但排除提示词、文档内容、工具结果正文、授权值、API key 和远程 MCP URL。

## Package boundaries（包边界）

The final cleanup moved Agent Memory persistence entities/mappers under `agent.memory.infrastructure.persistence`, kept roles and session types under `agent.memory.domain`, and split Document Task domain enums from MyBatis persistence. Existing cohesive packages such as `agent.tool`, `agent.mcp`, `rag.evaluation`, and `rag.retrieval` were intentionally retained. This is a structural cleanup only; it does not rewrite the Agent loop, retrieval algorithms, task worker, MCP integration, or token-budget behavior.

最终清理把 Agent Memory 的持久化实体和 mapper 移到了 `agent.memory.infrastructure.persistence` 之下，把角色和会话类型保留在 `agent.memory.domain` 之下，并将 Document Task 的领域枚举与 MyBatis 持久化拆分开。诸如 `agent.tool`、`agent.mcp`、`rag.evaluation` 和 `rag.retrieval` 这些原本就内聚的包被有意保留。这只是一次结构性清理；它不会重写 Agent 循环、检索算法、任务 worker、MCP 集成或 token 预算行为。
