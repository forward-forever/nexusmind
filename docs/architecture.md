# NexusMind Architecture

## System overview

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

## Ingestion and durable work

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

## Retrieval and RAG

Dense retrieval provides semantic matching, while BM25 provides lexical matching. Each route applies MySQL visibility validation before Application RRF. Hybrid executes Dense and BM25 concurrently but preserves the same candidate depth, RRF formula, deterministic tie-break, failure behavior, and provenance. Hybrid Rerank sends the RRF TopN to `qwen3.7-text-rerank` and maps results by provider index.

`RetrievalResult.model` and `dimension` were reviewed during final cleanup. They remain as optional execution metadata because evaluation reports and the retrieval debug response use them for reproducibility; non-vector routes may leave them absent/zero. Removing them would create broad type churn without changing behavior, so this P2 was documented rather than expanded into a retrieval-model redesign.

Fixed RAG is intentionally predictable:

```text
Question → configured RetrievalService → token-budgeted rank prefix
         → Prompt → ChatModel → SSE answer + only the sources the model saw
```

The product default remains DENSE because the current benchmark is saturated and provides no evidence that more latency and provider cost should be enabled by default.

## Agent

```text
                    ┌→ search_knowledge_base → Retrieval V2
Agent LLM → tool ───┤
                    ├→ get_document_context → MySQL chunks
                    └→ allowlisted MCP tool → Remote MCP server
      ↑                                      │
      └──────────── tool result ─────────────┘
```

The Java layer does not hard-code Search → Context. Each next action comes from a real model `tool_call`. Native tools obtain `knowledgeBaseId`, the deadline, source registry, and run budget from server-side ToolContext. MCP is disabled by default, Streamable HTTP only, startup-discovered, allowlisted, name-validated, and treated as an external untrusted capability.

Cross-request memory persists only `USER` and final `ASSISTANT` messages. Current-run assistant tool calls and tool responses remain execution state. A DB-time session lease allows one active run per session; `runId` fences release and final memory commit. Source IDs such as `S1` are scoped to one Agent Run, and historical source markers are sanitized before reuse.

Application token budgets constrain RAG context, historical memory, each tool result, total run tool results, and every model turn. Estimates are conservative local approximations, not exact Qwen token counts.

## Data ownership

| Component | Ownership |
|---|---|
| MySQL `knowledge_*` | Business source of truth for KBs, documents and chunks |
| Milvus | Derived, rebuildable retrieval projection |
| `knowledge_document_task` | Durable execution coordination, not business truth |
| Agent Session / Message | Cross-request conversational outcome |
| AgentRunContext | Single-run counters, deadline, source identity and tool budget |
| MCP Server | External, untrusted capability governed by local policy |

## Failure model

- Provider transient failures: bounded retry with backoff and jitter.
- Provider permanent failures: immediate failure; no alternate-model fallback.
- Streaming failure after observable output or tool execution: fail without replay.
- Hybrid route failure: whole Hybrid request fails; an empty route is still valid.
- Document worker crash: stale heartbeat recovery plus business-state reconciliation.
- Agent session contention: atomic DB lease; crash recovery through expiry.
- Context overflow: deterministic application budget error before provider invocation.
- MCP failure: tool error; no MCP-specific automatic retry.

## Guardrails and observability

Agent limits include five tool invocations, one absolute deadline, minimal schemas, run-scoped source IDs, session/KB binding, token budgets, and untrusted tool-result rules. Actuator exposes health, info and Prometheus only. Micrometer metrics use the `nexusmind.*` namespace and low-cardinality tags. Logs carry execution identity and outcome but exclude prompts, document contents, tool result bodies, authorization values, API keys, and remote MCP URLs.

## Package boundaries

The final cleanup moved Agent Memory persistence entities/mappers under `agent.memory.infrastructure.persistence`, kept roles and session types under `agent.memory.domain`, and split Document Task domain enums from MyBatis persistence. Existing cohesive packages such as `agent.tool`, `agent.mcp`, `rag.evaluation`, and `rag.retrieval` were intentionally retained. This is a structural cleanup only; it does not rewrite the Agent loop, retrieval algorithms, task worker, MCP integration, or token-budget behavior.
