# Agent Foundation（Agent 基础）

Checkpoint 12 established the first V3 agent loop. Checkpoint 13 keeps it stateless and adds a second read-only tool for model-directed local context expansion.

Checkpoint 12 建立了第一个 V3 Agent 循环。Checkpoint 13 让它保持无状态，并新增了第二个只读工具，用于由模型主导的局部上下文扩展。

## Agent versus fixed RAG（Agent 与固定式 RAG 对比）

The V1 RAG endpoint always performs retrieval before calling the chat model:

V1 RAG 端点总是在调用对话模型之前先执行检索：

```text
Question → RetrievalService → Context → ChatModel → Answer
```

The Agent endpoint gives the model a tool definition and lets the model decide whether retrieval is needed:

Agent 端点给模型一个工具定义，让模型自行决定是否需要检索：

```text
User → qwen3.5-flash
          │
          ├─ no tool call → Final Answer
          │
          └─ search_knowledge_base tool call
                    ↓
              RetrievalService
                    ↓
              structured tool result
                    ↓
              qwen3.5-flash
                    │
                    ├─ enough context → Final Answer
                    │
                    └─ get_document_context(S1)
                              ↓
                        surrounding MySQL chunks
                              ↓
                        qwen3.5-flash → Final Answer
```

`KnowledgeSearchTool` calls `RetrievalService` directly. It never calls `RagChatService`, so one user request does not create a nested LLM/RAG/LLM chain.

`KnowledgeSearchTool` 直接调用 `RetrievalService`。它从不调用 `RagChatService`，因此一次用户请求不会产生嵌套的 LLM/RAG/LLM 调用链。

## User-controlled Spring AI tool loop（用户可控的 Spring AI 工具循环）

Spring AI 2.0.1 can run tool loops automatically through `ToolCallingAdvisor`. NexusMind disables that advisor only for each Agent request with `AdvisorParams.toolCallingAdvisorAutoRegister(false)` and drives the loop itself:

Spring AI 2.0.1 可以通过 `ToolCallingAdvisor` 自动运行工具循环。NexusMind 仅针对每个 Agent 请求，用 `AdvisorParams.toolCallingAdvisorAutoRegister(false)` 禁用该 advisor，并自行驱动这个循环：

1. `ChatClient` streams one model turn.
  - `ChatClient` 流式输出一个模型轮次。
2. Natural-language chunks are forwarded immediately as `assistant_delta`.
  - 自然语言 chunk 会立即以 `assistant_delta` 转发出去。
3. `ChatClientMessageAggregator` builds the complete `ChatResponse` for that turn.
  - `ChatClientMessageAggregator` 组装该轮次完整的 `ChatResponse`。
4. If the response contains tool calls, the whole batch is checked against the run limit.
  - 如果响应中包含工具调用，则整批调用都要对照 run 限制进行检查。
5. `ToolCallingManager` executes the attached callbacks.
  - `ToolCallingManager` 执行挂载的回调。
6. The next `Prompt` uses `ToolExecutionResult.conversationHistory()`.
  - 下一个 `Prompt` 使用 `ToolExecutionResult.conversationHistory()`。
7. The loop stops only when the model returns a turn without tool calls or the run fails.
  - 只有当模型返回一个不含工具调用的轮次，或者 run 失败时，循环才会停止。

This choice gives NexusMind explicit `tool_start`, `tool_result`, and `tool_error` events, an application-level total tool-call limit, and one absolute deadline. It is an observability/control choice, not a claim that Spring AI automatic tool calling is unsuitable.

这一选择让 NexusMind 获得显式的 `tool_start`、`tool_result` 和 `tool_error` 事件、应用级的工具调用总次数上限，以及一个绝对截止时间。这是出于可观测性与可控性的选择，并不意味着 Spring AI 的自动工具调用不合适。

The two Agent tools are attached per call. They are not `defaultTool` instances, and the V1 `RagChatService` never receives them. There is no Java branch that automatically calls context after search: every step starts from the model's parsed `tool_calls` response.

这两个 Agent 工具是每次调用时挂载的。它们不是 `defaultTool` 实例，V1 的 `RagChatService` 也永远不会收到它们。不存在“检索之后自动调用上下文”的 Java 分支：每一步都从模型解析出的 `tool_calls` 响应开始。

## KnowledgeSearchTool contract（KnowledgeSearchTool 契约）

Tool name:

工具名称：

```text
search_knowledge_base
```

The model-visible input schema contains only:

模型可见的输入 schema 仅包含：

```json
{"query":"MVCC Read View"}
```

The HTTP path supplies `knowledgeBaseId`. NexusMind puts it and the plain `AgentRunContext` in Spring AI `ToolContext`; ToolContext values are application data and do not appear in the model-visible JSON Schema. No `ThreadLocal`, request holder, or static mutable request state is used.

HTTP 路径提供 `knowledgeBaseId`。NexusMind 把它和普通的 `AgentRunContext` 放入 Spring AI 的 `ToolContext`；ToolContext 的值属于应用数据，不会出现在模型可见的 JSON Schema 中。没有使用 `ThreadLocal`、请求持有者或静态可变请求状态。

Server policy selects the Retriever and TopK:

服务端策略选择 retriever 和 TopK：

```yaml
nexusmind:
  agent:
    max-tool-calls: 5
    max-duration: 30s
    knowledge-search:
      retriever: DENSE
      top-k: 5
    document-context:
      before-chunks: 1
      after-chunks: 1
```

`KnowledgeSearchTool` resolves that type through `RetrievalServiceRegistry`. The model cannot select a KB ID, Retriever, model, or TopK.

`KnowledgeSearchTool` 通过 `RetrievalServiceRegistry` 解析该类型。模型无法选择 KB ID、retriever、模型或 TopK。

The model receives a stable JSON result:

模型收到稳定的 JSON 结果：

```json
{
  "found": true,
  "query": "MVCC Read View",
  "items": [
    {
      "sourceId": "S1",
      "chunkId": 123,
      "documentId": 10,
      "fileName": "mysql.pdf",
      "pageNo": 17,
      "sectionTitle": "Read View",
      "content": "..."
    }
  ]
}
```

Retrieval scores, embedding configuration, RRF metadata, and rerank provenance are intentionally excluded. Empty retrieval is a successful tool result with `found=false` and an empty item list.

检索分数、embedding 配置、RRF 元数据和 rerank 来源都被刻意排除。空检索是一个成功的工具结果，其 `found=false` 且条目列表为空。

`AgentSourceRegistry` keeps a bidirectional `chunkId ↔ S1/S2/...` mapping stable for one run. Repeated retrieval or context expansion of the same chunk reuses its source ID. The registry is in memory only and is discarded when the request ends, so a source ID from another HTTP request cannot be resolved.

`AgentSourceRegistry` 在一个 run 内维护稳定的 `chunkId ↔ S1/S2/...` 双向映射。重复检索或对同一 chunk 做上下文扩展时会复用其来源 ID。该注册表只存在于内存中，并在请求结束时被丢弃，因此来自另一个 HTTP 请求的来源 ID 无法被解析。

## DocumentContextTool contract（DocumentContextTool 契约）

Tool name:

工具名称：

```text
get_document_context
```

The model-visible input schema contains only a source discovered earlier in the same run:

模型可见的输入 schema 仅包含同一 run 中先前已发现的来源：

```json
{"sourceId":"S1"}
```

`knowledgeBaseId`, `documentId`, `chunkId`, window size, and repository details stay in application `ToolContext` or server policy. The tool resolves the source through the run-scoped registry, loads the target chunk from MySQL, validates that its document still belongs to the current knowledge base and is `READY + INDEXED`, then queries by:

`knowledgeBaseId`、`documentId`、`chunkId`、窗口大小和仓储细节都留在应用的 `ToolContext` 或服务端策略中。该工具通过 run 级别的注册表解析来源，从 MySQL 加载目标 chunk，校验其文档仍属于当前知识库且状态为 `READY + INDEXED`，然后按以下条件查询：

```text
document_id = target.document_id
AND chunk_index BETWEEN targetIndex - 1 AND targetIndex + 1
ORDER BY chunk_index ASC
```

It never uses a primary-key range and never calls a Retriever. This preserves document boundaries and makes the tool a context-expansion operation rather than another search algorithm.

它从不使用主键范围，也从不调用 retriever。这样既保持了文档边界，也使该工具成为一种上下文扩展操作，而不是又一种检索算法。

The complete model result contains `sourceId`, filename, page/section metadata, content, and `isTarget`. Newly discovered neighbors are registered in the same source registry, so they are available to citations and automatically appear in the final `done.sources`. Browser `tool_result` events still omit content.

完整的模型结果包含 `sourceId`、文件名、页码/章节元数据、内容和 `isTarget`。新发现的相邻 chunk 会注册到同一个来源注册表中，因此可供引用使用，并自动出现在最终的 `done.sources` 中。浏览器的 `tool_result` 事件仍然省略内容。

An unknown ID such as `S99`, or a source whose document is no longer business-visible, is a normal structured result (`UNKNOWN_SOURCE` or `SOURCE_UNAVAILABLE`) with no items. Repository failures remain real tool failures and terminate the run.

像 `S99` 这样的未知 ID，或者文档已不再业务可见的来源，都是正常的结构化结果（`UNKNOWN_SOURCE` 或 `SOURCE_UNAVAILABLE`），且不含任何条目。仓储层故障仍然是真正的工具故障，会终止 run。

## SSE contract（SSE 契约）

Endpoint:

端点：

```text
POST /api/knowledge-bases/{knowledgeBaseId}/agent/chat
Content-Type: application/json
Accept: text/event-stream
```

All named SSE events contain the same run UUID in `runId`:

所有具名 SSE 事件都在 `runId` 中包含相同的 run UUID：

| Event | Meaning |
|---|---|
| `assistant_delta` | Real natural-language model delta |
| `tool_start` | One tool invocation has started; includes safe arguments |
| `tool_result` | Tool completed; contains source metadata but not chunk bodies |
| `tool_error` | Tool failed; contains a safe code and message |
| `done` | Run completed; contains counts, duration, and accumulated sources |
| `error` | Run failed; no `done` follows |

| 事件 | 含义 |
|---|---|
| `assistant_delta` | 真实的自然语言模型增量 |
| `tool_start` | 一次工具调用已开始；包含安全的参数 |
| `tool_result` | 工具已完成；包含来源元数据，但不含 chunk 正文 |
| `tool_error` | 工具失败；包含安全的错误码和消息 |
| `done` | run 已完成；包含计数、耗时和累计来源 |
| `error` | run 失败；其后不会有 `done` |

`tool_result` omits full chunk content from the browser payload. The complete structured result is sent only back to the model. `done.sources` is the browser's authoritative mapping from `[S1]` to chunk/document/file/page metadata.

`tool_result` 在浏览器载荷中省略完整的 chunk 内容。完整的结构化结果只回传给模型。`done.sources` 是浏览器端从 `[S1]` 到 chunk/文档/文件/页码元数据的权威映射。

## Limits and deadline（限制与截止时间）

`max-tool-calls` counts actual invocations, not loop iterations. If a model turn requests a batch that would cross the limit, none of that batch is executed.

`max-tool-calls` 统计的是实际调用次数，而不是循环迭代次数。如果某个模型轮次请求的一批调用会超出该上限，那么这一批调用一个都不会执行。

`max-duration` is an absolute request deadline measured from run creation. Remaining time is checked before each model call, before tool execution, and before the next loop. Model and tool publishers receive only the remaining timeout; the budget is not reset for each turn. The existing 150-second MVC async timeout remains above the 30-second Agent deadline.

`max-duration` 是从 run 创建开始计量的绝对请求截止时间。在每次模型调用之前、工具执行之前以及进入下一轮循环之前都会检查剩余时间。模型和工具的发布者只拿到剩余的 timeout；时间预算不会每轮重置。现有的 150 秒 MVC 异步 timeout 仍然高于 30 秒的 Agent 截止时间。

## Prompt-injection boundary（提示词注入边界）

Knowledge chunks and tool outputs are untrusted data. The Agent system prompt explicitly says that document text asking it to ignore instructions, change roles, reveal secrets, execute commands, or call tools is reference data rather than an instruction. Tool selection must not be driven by instructions inside retrieved documents.

知识 chunk 和工具输出都是不可信数据。Agent 系统提示词明确指出：要求它忽略指令、改变角色、泄露机密、执行命令或调用工具的文档文本属于参考数据，而不是指令。工具的选择绝不能由检索到的文档内部的指令来驱动。

Both tools are read-only. Search results and surrounding chunks are equally untrusted, including text that asks the model to call tools, reveal prompts, or ignore rules. This is defense in depth, not a guarantee that prompt injection is completely solved.

两个工具都是只读的。检索结果和相邻 chunk 同样不可信，包括要求模型调用工具、泄露提示词或忽略规则的文本。这是纵深防御，并不保证提示词注入已被彻底解决。

## Current boundaries（当前边界）

- Two read-only tools only: discovery through `search_knowledge_base` and local expansion through `get_document_context`.
  - 只有两个只读工具：通过 `search_knowledge_base` 发现内容，通过 `get_document_context` 做局部扩展。
- Stateless per HTTP request; no conversation or memory persistence.
  - 每个 HTTP 请求都是无状态的；没有会话或记忆持久化。
- No tool retry, fallback, recovery, approval, or circuit breaker.
  - 没有工具重试、回退、恢复、审批或熔断器。
- Tool failure fails the whole run.
  - 工具失败会导致整个 run 失败。
- Agent retrieval defaults to DENSE, matching the frozen V2 product decision.
  - Agent 检索默认为 DENSE，与已冻结的 V2 产品决策一致。
- No Agent UI; use curl for Checkpoint 13.
  - 没有 Agent UI；Checkpoint 13 请使用 curl。
- No Java citation validation or repair.
  - 没有 Java 层的引用校验或修复。

## Manual verification（手工验证）

Start the local server with the existing datasource, Milvus, embedding, and chat environment, and enable the Agent without printing those values:

使用现有的数据源、Milvus、embedding 和对话环境启动本地服务，并启用 Agent，且不打印这些值：

```bash
cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--nexusmind.agent.enabled=true"
```

Direct answer (expected: `assistant_delta`, then `done`, with no tool events):

直接回答（预期：`assistant_delta`，然后是 `done`，没有任何工具事件）：

```bash
curl -N \
  -X POST \
  http://localhost:8080/api/knowledge-bases/<KB_ID>/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好，你是谁？"}'
```

Knowledge search (expected: `tool_start`, `tool_result`, `assistant_delta`, `done`):

知识检索（预期：`tool_start`、`tool_result`、`assistant_delta`、`done`）：

```bash
curl -N \
  -X POST \
  http://localhost:8080/api/knowledge-bases/<KB_ID>/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"根据当前知识库解释 MVCC 的 Read View。"}'
```

Multi-tool chaining (expected: search lifecycle, context lifecycle, answer, then done):

多工具串联（预期：检索生命周期、上下文生命周期、回答，然后是 done）：

```bash
curl -N \
  -X POST \
  http://localhost:8080/api/knowledge-bases/<KB_ID>/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请先搜索当前知识库中关于 MVCC Read View 的资料，然后查看最相关来源附近的上下文，最后结合这些资料解释 Read View 与 RC、RR 隔离级别的关系。"}'
```

The INFO log should show three independently parsed model turns:

INFO 日志应显示三个独立解析的模型轮次：

```text
turn=1 hasToolCalls=true  requestedToolNames=[search_knowledge_base]
turn=2 hasToolCalls=true  requestedToolNames=[get_document_context]
turn=3 hasToolCalls=false requestedToolNames=[]
```

This is the direct evidence that search and context expansion are model decisions rather than a fixed Java workflow. The model may decide search alone is sufficient, or select a source other than `S1`; both are valid within the tool and deadline guardrails.

这是直接证据，说明检索和上下文扩展是模型的决定，而不是固定的 Java 工作流。模型可能认为仅检索就足够了，也可能选择 `S1` 以外的来源；在工具和截止时间的护栏内，两者都是合法的。
