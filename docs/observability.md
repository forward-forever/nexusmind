# Observability

NexusMind uses Spring Boot Actuator, Micrometer, and the Prometheus registry. It provides an instrumentation surface without bundling a metrics backend, dashboard, tracing collector, or log aggregation stack.

## Endpoints

Only these Actuator endpoints are exposed over HTTP:

```text
GET /actuator/health
GET /actuator/info
GET /actuator/prometheus
```

Sensitive endpoints such as `env`, `configprops`, `beans`, `heapdump`, and `loggers` are not exposed. Standard Spring health contributors cover the application and, under the local profile, the DataSource. The MCP health contributor reads the immutable startup-discovery snapshot and never makes a remote call during a health request. Disabled MCP is healthy with `enabled=false`.

## Metrics taxonomy

All application metrics use the `nexusmind.*` namespace.

| Area | Metrics |
|---|---|
| RAG | `nexusmind.rag.requests`, `.duration`, `.errors` |
| Retrieval | `nexusmind.retrieval.requests`, `.duration`, `.errors`, `.route.duration` |
| Provider | `nexusmind.ai.provider.calls`, `.duration`, `.errors`, `.retries` |
| Agent | `nexusmind.agent.runs`, `.duration`, `.errors`, `.model.turns` |
| Agent tools | `nexusmind.agent.tool.calls`, `.duration`, `.errors` |
| MCP | `nexusmind.mcp.calls`, `.duration`, `.errors` |
| Document tasks | `nexusmind.document.task.enqueued`, `.completed`, `.failed`, `.recovered`, `.duration` |
| Context | `nexusmind.context.budget.exceeded`, `.truncations` |
| Session | `nexusmind.agent.session.busy`, `.lease_lost` |
| Citation | `nexusmind.agent.citation.invalid` |

Provider `calls` and duration are recorded per actual provider attempt; `retries` records the additional attempts selected by the bounded retry policy. The provider operation tag is normalized to the fixed categories `chat`, `embedding`, or `rerank`. Retrieval route timing uses fixed `dense` and `bm25` values so parallel-route latency remains observable.

Micrometer converts dotted names for Prometheus output. Examples include:

```text
nexusmind_agent_runs_total
nexusmind_agent_tool_calls_total
nexusmind_ai_provider_retries_total
nexusmind_document_task_completed_total
nexusmind_context_budget_exceeded_total
```

Inspect local metrics with:

```bash
curl http://localhost:8080/actuator/prometheus
```

## Tag policy

Allowed tags are bounded enums or fixed categories such as `retriever`, `outcome`, `operation`, `provider`, `failureCategory`, `taskType`, `toolType`, and `transport`.

The following must never be metric tags:

```text
runId sessionId taskId documentId knowledgeBaseId
query prompt MCP tool name remote URL exception message
```

Dynamic MCP tool names remain in structured log messages only. This keeps Prometheus series cardinality bounded as sessions, documents, user input, and external tools grow.

## Logging fields

The existing text log format is retained. Logs use consistent named fields:

- Agent: `runId`, `sessionId`, `modelTurn`, `toolCount`, `duration`, `outcome`.
- Document task: `taskId`, `documentId`, `taskType`, `attempt`, `workerId`, `duration`, `outcome`.
- Retrieval: `retriever`, `topK`, `resultCount`, `duration`; query text is not logged at INFO.
- Provider: provider/operation, bounded attempt, classification, status and delay.
- MCP: tool name, duration and outcome; never arguments, result body, URL or credentials.

Logs must not contain API keys, Authorization headers, full prompts, conversation history, chunk bodies, tool result contents, or configured remote MCP URLs.

## Health semantics

Health is intentionally shallow and inexpensive. It reports component readiness rather than exercising paid AI providers, performing vector search, or invoking remote MCP servers. MCP startup discovery failures fail application initialization when MCP is enabled; `/health` reports the resulting startup snapshot only.

## Testing

Metric unit tests use `SimpleMeterRegistry` and verify representative success, error, retry, tool, task, context and MCP counters. They also assert that forbidden high-cardinality tag keys are absent. Health and citation validation have deterministic offline tests. Default Maven tests do not call Qwen, Milvus, a remote MCP server, or a real database.

## Scope boundary

NexusMind exposes health and scrapeable metrics but does not ship Grafana dashboards, OpenTelemetry tracing, Jaeger, an alerting stack, or centralized logs. Those omissions are explicit project boundaries, not hidden claims of complete observability infrastructure.
