# Agent Foundation

Checkpoint 12 established the first V3 agent loop. Checkpoint 13 keeps it stateless and adds a second read-only tool for model-directed local context expansion.

## Agent versus fixed RAG

The V1 RAG endpoint always performs retrieval before calling the chat model:

```text
Question → RetrievalService → Context → ChatModel → Answer
```

The Agent endpoint gives the model a tool definition and lets the model decide whether retrieval is needed:

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

## User-controlled Spring AI tool loop

Spring AI 2.0.1 can run tool loops automatically through `ToolCallingAdvisor`. NexusMind disables that advisor only for each Agent request with `AdvisorParams.toolCallingAdvisorAutoRegister(false)` and drives the loop itself:

1. `ChatClient` streams one model turn.
2. Natural-language chunks are forwarded immediately as `assistant_delta`.
3. `ChatClientMessageAggregator` builds the complete `ChatResponse` for that turn.
4. If the response contains tool calls, the whole batch is checked against the run limit.
5. `ToolCallingManager` executes the attached callbacks.
6. The next `Prompt` uses `ToolExecutionResult.conversationHistory()`.
7. The loop stops only when the model returns a turn without tool calls or the run fails.

This choice gives NexusMind explicit `tool_start`, `tool_result`, and `tool_error` events, an application-level total tool-call limit, and one absolute deadline. It is an observability/control choice, not a claim that Spring AI automatic tool calling is unsuitable.

The two Agent tools are attached per call. They are not `defaultTool` instances, and the V1 `RagChatService` never receives them. There is no Java branch that automatically calls context after search: every step starts from the model's parsed `tool_calls` response.

## KnowledgeSearchTool contract

Tool name:

```text
search_knowledge_base
```

The model-visible input schema contains only:

```json
{"query":"MVCC Read View"}
```

The HTTP path supplies `knowledgeBaseId`. NexusMind puts it and the plain `AgentRunContext` in Spring AI `ToolContext`; ToolContext values are application data and do not appear in the model-visible JSON Schema. No `ThreadLocal`, request holder, or static mutable request state is used.

Server policy selects the Retriever and TopK:

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

The model receives a stable JSON result:

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

`AgentSourceRegistry` keeps a bidirectional `chunkId ↔ S1/S2/...` mapping stable for one run. Repeated retrieval or context expansion of the same chunk reuses its source ID. The registry is in memory only and is discarded when the request ends, so a source ID from another HTTP request cannot be resolved.

## DocumentContextTool contract

Tool name:

```text
get_document_context
```

The model-visible input schema contains only a source discovered earlier in the same run:

```json
{"sourceId":"S1"}
```

`knowledgeBaseId`, `documentId`, `chunkId`, window size, and repository details stay in application `ToolContext` or server policy. The tool resolves the source through the run-scoped registry, loads the target chunk from MySQL, validates that its document still belongs to the current knowledge base and is `READY + INDEXED`, then queries by:

```text
document_id = target.document_id
AND chunk_index BETWEEN targetIndex - 1 AND targetIndex + 1
ORDER BY chunk_index ASC
```

It never uses a primary-key range and never calls a Retriever. This preserves document boundaries and makes the tool a context-expansion operation rather than another search algorithm.

The complete model result contains `sourceId`, filename, page/section metadata, content, and `isTarget`. Newly discovered neighbors are registered in the same source registry, so they are available to citations and automatically appear in the final `done.sources`. Browser `tool_result` events still omit content.

An unknown ID such as `S99`, or a source whose document is no longer business-visible, is a normal structured result (`UNKNOWN_SOURCE` or `SOURCE_UNAVAILABLE`) with no items. Repository failures remain real tool failures and terminate the run.

## SSE contract

Endpoint:

```text
POST /api/knowledge-bases/{knowledgeBaseId}/agent/chat
Content-Type: application/json
Accept: text/event-stream
```

All named SSE events contain the same run UUID in `runId`:

| Event | Meaning |
|---|---|
| `assistant_delta` | Real natural-language model delta |
| `tool_start` | One tool invocation has started; includes safe arguments |
| `tool_result` | Tool completed; contains source metadata but not chunk bodies |
| `tool_error` | Tool failed; contains a safe code and message |
| `done` | Run completed; contains counts, duration, and accumulated sources |
| `error` | Run failed; no `done` follows |

`tool_result` omits full chunk content from the browser payload. The complete structured result is sent only back to the model. `done.sources` is the browser's authoritative mapping from `[S1]` to chunk/document/file/page metadata.

## Limits and deadline

`max-tool-calls` counts actual invocations, not loop iterations. If a model turn requests a batch that would cross the limit, none of that batch is executed.

`max-duration` is an absolute request deadline measured from run creation. Remaining time is checked before each model call, before tool execution, and before the next loop. Model and tool publishers receive only the remaining timeout; the budget is not reset for each turn. The existing 150-second MVC async timeout remains above the 30-second Agent deadline.

## Prompt-injection boundary

Knowledge chunks and tool outputs are untrusted data. The Agent system prompt explicitly says that document text asking it to ignore instructions, change roles, reveal secrets, execute commands, or call tools is reference data rather than an instruction. Tool selection must not be driven by instructions inside retrieved documents.

Both tools are read-only. Search results and surrounding chunks are equally untrusted, including text that asks the model to call tools, reveal prompts, or ignore rules. This is defense in depth, not a guarantee that prompt injection is completely solved.

## Current boundaries

- Two read-only tools only: discovery through `search_knowledge_base` and local expansion through `get_document_context`.
- Stateless per HTTP request; no conversation or memory persistence.
- No tool retry, fallback, recovery, approval, or circuit breaker.
- Tool failure fails the whole run.
- Agent retrieval defaults to DENSE, matching the frozen V2 product decision.
- No Agent UI; use curl for Checkpoint 13.
- No Java citation validation or repair.

## Manual verification

Start the local server with the existing datasource, Milvus, embedding, and chat environment, and enable the Agent without printing those values:

```bash
cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--nexusmind.agent.enabled=true"
```

Direct answer (expected: `assistant_delta`, then `done`, with no tool events):

```bash
curl -N \
  -X POST \
  http://localhost:8080/api/knowledge-bases/<KB_ID>/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好，你是谁？"}'
```

Knowledge search (expected: `tool_start`, `tool_result`, `assistant_delta`, `done`):

```bash
curl -N \
  -X POST \
  http://localhost:8080/api/knowledge-bases/<KB_ID>/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"根据当前知识库解释 MVCC 的 Read View。"}'
```

Multi-tool chaining (expected: search lifecycle, context lifecycle, answer, then done):

```bash
curl -N \
  -X POST \
  http://localhost:8080/api/knowledge-bases/<KB_ID>/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请先搜索当前知识库中关于 MVCC Read View 的资料，然后查看最相关来源附近的上下文，最后结合这些资料解释 Read View 与 RC、RR 隔离级别的关系。"}'
```

The INFO log should show three independently parsed model turns:

```text
turn=1 hasToolCalls=true  requestedToolNames=[search_knowledge_base]
turn=2 hasToolCalls=true  requestedToolNames=[get_document_context]
turn=3 hasToolCalls=false requestedToolNames=[]
```

This is the direct evidence that search and context expansion are model decisions rather than a fixed Java workflow. The model may decide search alone is sufficient, or select a source other than `S1`; both are valid within the tool and deadline guardrails.
