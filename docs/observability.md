# Observability（可观测性）

NexusMind uses Spring Boot Actuator, Micrometer, and the Prometheus registry. It provides an instrumentation surface without bundling a metrics backend, dashboard, tracing collector, or log aggregation stack.

NexusMind 使用 Spring Boot Actuator、Micrometer 和 Prometheus registry。它提供了 instrumentation 能力，但不捆绑指标后端、看板、链路追踪收集器或日志聚合栈。

## Endpoints（端点）

Only these Actuator endpoints are exposed over HTTP:

只有以下 Actuator 端点通过 HTTP 暴露：

```text
GET /actuator/health
GET /actuator/info
GET /actuator/prometheus
```

Sensitive endpoints such as `env`, `configprops`, `beans`, `heapdump`, and `loggers` are not exposed. Standard Spring health contributors cover the application and, under the local profile, the DataSource. The MCP health contributor reads the immutable startup-discovery snapshot and never makes a remote call during a health request. Disabled MCP is healthy with `enabled=false`.

诸如 `env`、`configprops`、`beans`、`heapdump` 和 `loggers` 这类敏感端点不会被暴露。标准的 Spring health contributor 覆盖应用本身，并且在 local profile 下覆盖 DataSource。MCP health contributor 读取不可变的启动发现快照，在 health 请求期间从不发起远程调用。MCP 被禁用时状态为健康，并带有 `enabled=false`。

## Metrics taxonomy（指标分类）

All application metrics use the `nexusmind.*` namespace.

所有应用指标都使用 `nexusmind.*` 命名空间。

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

| 领域 | 指标 |
|---|---|
| RAG | `nexusmind.rag.requests`、`.duration`、`.errors` |
| 检索 | `nexusmind.retrieval.requests`、`.duration`、`.errors`、`.route.duration` |
| 提供商 | `nexusmind.ai.provider.calls`、`.duration`、`.errors`、`.retries` |
| Agent | `nexusmind.agent.runs`、`.duration`、`.errors`、`.model.turns` |
| Agent 工具 | `nexusmind.agent.tool.calls`、`.duration`、`.errors` |
| MCP | `nexusmind.mcp.calls`、`.duration`、`.errors` |
| 文档任务 | `nexusmind.document.task.enqueued`、`.completed`、`.failed`、`.recovered`、`.duration` |
| 上下文 | `nexusmind.context.budget.exceeded`、`.truncations` |
| 会话 | `nexusmind.agent.session.busy`、`.lease_lost` |
| 引用 | `nexusmind.agent.citation.invalid` |

Provider `calls` and duration are recorded per actual provider attempt; `retries` records the additional attempts selected by the bounded retry policy. The provider operation tag is normalized to the fixed categories `chat`, `embedding`, or `rerank`. Retrieval route timing uses fixed `dense` and `bm25` values so parallel-route latency remains observable.

提供商的 `calls` 和 duration 按每次真实的提供商尝试记录；`retries` 记录有界重试策略所选择发起的额外尝试次数。提供商的 operation 标签被归一化为固定类别 `chat`、`embedding` 或 `rerank`。检索路由耗时使用固定的 `dense` 和 `bm25` 值，使并行路由的延迟保持可观测。

Micrometer converts dotted names for Prometheus output. Examples include:

Micrometer 会为 Prometheus 输出转换带点号的名称。例如：

```text
nexusmind_agent_runs_total
nexusmind_agent_tool_calls_total
nexusmind_ai_provider_retries_total
nexusmind_document_task_completed_total
nexusmind_context_budget_exceeded_total
```

Inspect local metrics with:

用以下命令查看本地指标：

```bash
curl http://localhost:8080/actuator/prometheus
```

## Tag policy（标签策略）

Allowed tags are bounded enums or fixed categories such as `retriever`, `outcome`, `operation`, `provider`, `failureCategory`, `taskType`, `toolType`, and `transport`.

允许的标签是有界枚举或固定类别，例如 `retriever`、`outcome`、`operation`、`provider`、`failureCategory`、`taskType`、`toolType` 和 `transport`。

The following must never be metric tags:

以下内容永远不能作为指标标签：

```text
runId sessionId taskId documentId knowledgeBaseId
query prompt MCP tool name remote URL exception message
```

Dynamic MCP tool names remain in structured log messages only. This keeps Prometheus series cardinality bounded as sessions, documents, user input, and external tools grow.

动态的 MCP 工具名称只保留在结构化日志消息中。这样随着会话、文档、用户输入和外部工具的增长，Prometheus 的序列基数依然有界。

## Logging fields（日志字段）

The existing text log format is retained. Logs use consistent named fields:

保留现有的文本日志格式。日志使用一致的命名字段：

- Agent: `runId`, `sessionId`, `modelTurn`, `toolCount`, `duration`, `outcome`.
  - Agent：`runId`、`sessionId`、`modelTurn`、`toolCount`、`duration`、`outcome`。
- Document task: `taskId`, `documentId`, `taskType`, `attempt`, `workerId`, `duration`, `outcome`.
  - 文档任务：`taskId`、`documentId`、`taskType`、`attempt`、`workerId`、`duration`、`outcome`。
- Retrieval: `retriever`, `topK`, `resultCount`, `duration`; query text is not logged at INFO.
  - 检索：`retriever`、`topK`、`resultCount`、`duration`；查询文本不会在 INFO 级别记录。
- Provider: provider/operation, bounded attempt, classification, status and delay.
  - 提供商：provider/operation、有界的尝试次数、分类、状态和延迟。
- MCP: tool name, duration and outcome; never arguments, result body, URL or credentials.
  - MCP：工具名称、耗时和结果；绝不记录参数、结果正文、URL 或凭据。

Logs must not contain API keys, Authorization headers, full prompts, conversation history, chunk bodies, tool result contents, or configured remote MCP URLs.

日志不得包含 API key、Authorization 头、完整的提示词、对话历史、chunk 正文、工具结果内容或已配置的远程 MCP URL。

## Health semantics（健康语义）

Health is intentionally shallow and inexpensive. It reports component readiness rather than exercising paid AI providers, performing vector search, or invoking remote MCP servers. MCP startup discovery failures fail application initialization when MCP is enabled; `/health` reports the resulting startup snapshot only.

健康检查刻意保持浅层且低开销。它报告组件就绪状态，而不会去实际调用付费的 AI 提供商、执行向量检索或调用远程 MCP 服务器。当 MCP 启用时，MCP 启动发现失败会导致应用初始化失败；`/health` 只报告由此得到的启动快照。

## Testing（测试）

Metric unit tests use `SimpleMeterRegistry` and verify representative success, error, retry, tool, task, context and MCP counters. They also assert that forbidden high-cardinality tag keys are absent. Health and citation validation have deterministic offline tests. Default Maven tests do not call Qwen, Milvus, a remote MCP server, or a real database.

指标单元测试使用 `SimpleMeterRegistry`，验证具有代表性的成功、错误、重试、工具、任务、上下文和 MCP 计数器。它们还会断言被禁止的高基数标签键不存在。健康检查和引用校验有确定性的离线测试。默认的 Maven 测试不会调用 Qwen、Milvus、远程 MCP 服务器或真实数据库。

## Scope boundary（范围边界）

NexusMind exposes health and scrapeable metrics but does not ship Grafana dashboards, OpenTelemetry tracing, Jaeger, an alerting stack, or centralized logs. Those omissions are explicit project boundaries, not hidden claims of complete observability infrastructure.

NexusMind 暴露健康检查和可抓取的指标，但不提供 Grafana 看板、OpenTelemetry 链路追踪、Jaeger、告警栈或集中式日志。这些缺失是明确的项目边界，而不是对完整可观测性基础设施的隐性宣称。
