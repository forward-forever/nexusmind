# NexusMind V3 Agent（NexusMind V3 Agent）

V3 adds a controlled Agent layer on top of V1 ingestion/RAG and V2 retrieval. It uses real qwen3.5-flash function calling, two read-only knowledge tools, structured SSE, bounded conversation memory, and objective behavior evaluation.

V3 在 V1 的文档入库/RAG 和 V2 的检索之上增加了一个受控的 Agent 层。它使用真实的 qwen3.5-flash function calling、两个只读知识工具、结构化 SSE、有界会话记忆，以及客观的行为评估。

## Architecture（架构）

```text
User / Agent Chat UI
        ↓
qwen3.5-flash Agent LLM
        ↓ model tool_calls
        ├── search_knowledge_base ──→ RetrievalService (V2) ──→ S1 / S2 / S3
        └── get_document_context(S1) ──→ MySQL Chunks ──→ nearby passages
        ↑                                      │
        └──────────── tool result ──────────────┘
        ↓
Final Answer + current-run Sources
        ↓
MySQL Conversation Memory (USER + FINAL ASSISTANT)
```

The application drives Spring AI's tool execution loop so it can publish stable tool events and enforce NexusMind guardrails. The model—not Java—decides whether to call a tool and which tool to call next.

由应用驱动 Spring AI 的工具执行循环，这样才能发布稳定的工具事件并强制执行 NexusMind 的护栏。决定是否调用工具以及下一步调用哪个工具的是模型，而不是 Java。

## Fixed RAG vs Agent（固定 RAG 与 Agent 的对比）

V1 Fixed RAG follows a predetermined pipeline:

V1 的固定 RAG 走一条预先确定的流水线：

```text
Query → Retrieval → Context → LLM → Answer
```

V3 Agent starts with the model:

V3 Agent 从模型开始：

```text
Query → LLM → decide whether to use a tool
            → inspect tool result
            → decide the next step
            → final answer
```

Both remain available in the Web UI. Fixed RAG is the predictable grounded-answer path; Agent Chat demonstrates conditional tool selection, multi-step execution, and cross-request conversational context.

两者在 Web UI 中都仍然可用。固定 RAG 是可预测的、有依据的答案路径；Agent Chat 则展示了条件式工具选择、多步执行和跨请求的会话上下文。

## Tools and Chaining（工具与链式调用）

`search_knowledge_base` discovers relevant sources through the configured `RetrievalService`. `get_document_context` accepts only a `sourceId` previously returned during the current run and loads nearby chunks from the same document.

`search_knowledge_base` 通过配置好的 `RetrievalService` 发现相关来源。`get_document_context` 只接受当前 run 中先前返回过的 `sourceId`，并从同一篇文档中加载相邻的 chunk。

```text
search_knowledge_base
        ↓
       S1
        ↓ model decides more context is needed
get_document_context(S1)
        ↓
nearby passages with stable source IDs
        ↓
final answer
```

This is not a fixed `search(); context(); chat();` Java workflow. A direct question can complete in one model turn, a complete search can skip context expansion, and a complex question can chain both tools based on actual model `tool_calls`.

这不是一个固定的 `search(); context(); chat();` Java 工作流。一个直接的问题可以在一个模型轮次内完成，一次完整的检索可以跳过上下文扩展，而一个复杂问题可以基于模型实际的 `tool_calls` 把两个工具串联起来。

## Conversation Memory（会话记忆）

```text
Session
├── Run 1
├── Run 2
└── Run 3
```

`sessionId` survives across HTTP requests while each request gets a new `runId`, deadline, counters, and SourceRegistry. MySQL stores only `USER` and final `ASSISTANT` messages. Tool calls, tool responses, invocation IDs, and execution trace do not enter cross-request memory. The prompt receives at most the latest 12 persisted messages by default. See [`agent-memory.md`](agent-memory.md) for transaction and failure semantics.

`sessionId` 跨 HTTP 请求持续存在，而每个请求都会得到新的 `runId`、截止时间、计数器和 SourceRegistry。MySQL 只存储 `USER` 消息和最终的 `ASSISTANT` 消息。工具调用、工具响应、调用 ID 和执行轨迹都不会进入跨请求记忆。默认情况下，提示词最多接收最近 12 条已持久化的消息。事务和失败语义参见 [`agent-memory.md`](agent-memory.md)。

## Source Scope and Citation（来源作用域与引用）

`S1`, `S2`, and related IDs belong to one Agent Run and are not persisted as Session identity. Historical Assistant text is stored exactly as the user saw it, but stale `[S<number>]` markers are removed before injection into a later prompt. Only current-run tool sources are valid citations.

`S1`、`S2` 及相关 ID 属于某一个 Agent Run，不会作为 Session 身份被持久化。历史 Assistant 文本按用户当时看到的样子原样存储，但陈旧的 `[S<number>]` 标记会在注入后续提示词之前被移除。只有当前 run 的工具来源才是有效引用。

The Web UI scopes Source DOM identity by `runId + sourceId`, so two Runs may both use `S1` without collision. Citation clicks expand and highlight only the Source card from the originating Run.

Web UI 用 `runId + sourceId` 来限定 Source 的 DOM 标识，因此两个 Run 可以都使用 `S1` 而不会冲突。点击引用只会展开并高亮来自原始 Run 的 Source 卡片。

Model Markdown is rendered with raw HTML disabled, sanitized with DOMPurify, and then displayed. Citation tokens are created by the application renderer rather than accepted as model-generated HTML.

模型输出的 Markdown 在禁用原始 HTML 的情况下渲染，经 DOMPurify 清洗后再展示。引用标记由应用的渲染器生成，而不是把模型生成的 HTML 当作 HTML 接受。

## Agent Workbench UI（Agent 工作台 UI）

The Vue SPA is organized as a tabbed Workbench: `Knowledge`, `Agent Chat`, `RAG Chat`, and `Retrieval Lab`. Functional tabs share one global Knowledge Base selector. Tab changes keep mounted Agent/RAG/Retrieval state, while changing the selected Knowledge Base intentionally resets KB-bound Session and query state.

这个 Vue SPA 被组织成一个带标签页的 Workbench：`Knowledge`、`Agent Chat`、`RAG Chat` 和 `Retrieval Lab`。各功能标签页共享同一个全局知识库选择器。切换标签页会保留已挂载的 Agent/RAG/Retrieval 状态，而更改所选知识库则会刻意重置与知识库绑定的 Session 和查询状态。

The Agent Chat tab provides a viewport-sized conversation area, streaming Assistant output, a stable composer, collapsible Tool Trace, collapsible run-scoped Sources, and safe clickable citations. It exposes observable tool lifecycle only and never hidden reasoning.

Agent Chat 标签页提供视口大小的对话区域、流式的 Assistant 输出、稳定的输入框、可折叠的 Tool Trace、可折叠的 run 级 Sources，以及安全可点击的引用。它只暴露可观察的工具生命周期，绝不暴露隐藏的推理过程。

## Structured SSE and Tool Trace（结构化 SSE 与工具追踪）

The Agent endpoint emits `assistant_delta`, `tool_start`, `tool_result`, `tool_error`, `done`, and `error`. Every event contains the same `sessionId` and per-request `runId`. The browser displays observable tool lifecycle, safe arguments, result counts, durations, source metadata, tool-call count, model-turn count, and total duration. It never displays hidden reasoning or chain-of-thought.

Agent 端点会发出 `assistant_delta`、`tool_start`、`tool_result`、`tool_error`、`done` 和 `error`。每个事件都包含相同的 `sessionId` 和每个请求各自的 `runId`。浏览器展示可观察的工具生命周期、安全参数、结果数量、耗时、来源元数据、工具调用次数、模型轮次和总耗时。它绝不展示隐藏的推理或思维链。

## Guardrails（护栏）

- `maxToolCalls = 5` per Agent Run.
  - 每个 Agent Run 的 `maxToolCalls = 5`。
- One absolute 30-second deadline covers all model turns and tools.
  - 一个 30 秒的绝对截止时间覆盖所有模型轮次和工具。
- Model-visible Tool Schemas expose only minimal parameters.
  - 模型可见的 Tool Schema 只暴露最小参数集。
- `knowledgeBaseId` comes from server-side `ToolContext`.
  - `knowledgeBaseId` 来自服务端的 `ToolContext`。
- `sourceId` prevents arbitrary internal Chunk ID access.
  - `sourceId` 防止对内部 Chunk ID 的任意访问。
- Context remains within the current KnowledgeBase and READY/INDEXED documents.
  - 上下文始终限定在当前知识库以及 READY/INDEXED 状态的文档范围内。
- Tool output and conversation history are untrusted data.
  - 工具输出和会话历史都是不可信数据。
- Sessions are bound to one KnowledgeBase.
  - Session 绑定到单个知识库。
- Failed, timed-out, tool-limited, or uncommitted Runs do not write Memory.
  - 失败、超时、触发工具上限或未提交的 Run 都不会写入记忆。
- `done` means both final answer generation and Memory commit completed.
  - `done` 意味着最终答案生成和记忆提交都已完成。

## Agent Behavior Evaluation（Agent 行为评估）

`evaluation/agent/agent-behavior.jsonl` contains 11 scenarios across DIRECT, SEARCH, MULTI_TOOL, INSUFFICIENT_KNOWLEDGE, MEMORY, and GUARDRAIL. The evaluator invokes the real `AgentChatService` and observes SSE events; it never implements search or context logic itself.

`evaluation/agent/agent-behavior.jsonl` 包含 11 个场景，覆盖 DIRECT、SEARCH、MULTI_TOOL、INSUFFICIENT_KNOWLEDGE、MEMORY 和 GUARDRAIL。评估器调用真实的 `AgentChatService` 并观察 SSE 事件；它自身从不实现检索或上下文逻辑。

Metrics cover scenario count, completion rate, exact Tool sequence match, unexpected Tool calls, average Tool calls/model turns/duration, and Session continuity. It intentionally does not grade answer quality and does not use an LLM judge. Default tests use a deterministic fake executor.

指标涵盖场景数量、完成率、Tool 序列完全匹配、非预期的 Tool 调用、平均 Tool 调用次数/模型轮次/耗时，以及 Session 连续性。它刻意不评估答案质量，也不使用 LLM 作为评判者。默认测试使用确定性的假执行器。

### Real evaluation command（真实评估命令）

This command invokes qwen3.5-flash and must only be run explicitly with valid local configuration:

该命令会调用 qwen3.5-flash，只能在具备有效本地配置时显式运行：

```bash
cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
set -a
source ../deploy/.env
set +a

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --nexusmind.rag.enabled=false --nexusmind.evaluation.enabled=false --nexusmind.agent-evaluation.enabled=true --knowledge-base-id=<KB_ID> --dataset=/Users/wude/IdeaProjects/nexusmind/evaluation/agent/agent-behavior.jsonl --output=/Users/wude/IdeaProjects/nexusmind/evaluation/agent/reports"
```

Reports are JSON plus Markdown. A non-perfect result is retained as actual model behavior; the dataset is not changed to game the score.

报告为 JSON 加 Markdown。不完美的结果会作为模型的实际行为被保留；不会为了让分数好看而修改数据集。

## Known Limitations（已知局限）

1. Agent cancellation remains best effort.
   - Agent 的取消仍然只是尽力而为。
2. There is no provider fallback or circuit breaker.
   - 没有提供商回退或熔断器。
3. There is no Session list, title, rename, or delete API/UI.
   - 没有 Session 列表、标题、重命名或删除的 API/UI。
4. Browser refresh loses visible UI history; MySQL memory remains.
   - 浏览器刷新会丢失可见的 UI 历史；MySQL 中的记忆仍然保留。
5. The local token estimator is conservative but not the exact Qwen tokenizer.
   - 本地 token 估算器偏保守，但并不是 Qwen 的精确 tokenizer。
6. There is no semantic or long-term user memory.
   - 没有语义记忆或长期用户记忆。
7. Invalid final citations are observed and warned about, but streaming answers are not rewritten.
   - 无效的最终引用会被观察并告警，但流式答案不会被重写。
8. V3 has exactly two native read-only knowledge tools; controlled external MCP tools are a V4 integration.
   - V3 恰好有两个原生只读知识工具；受控的外部 MCP 工具属于 V4 的集成内容。
9. Agent behavior evaluation is deliberately small and behavior-only.
   - Agent 行为评估刻意做得很小，且只关注行为。
10. Real behavior evaluation uses the normal Agent application path and persists its conversations; those rows are retained for diagnosis and explicitly identified by `agent_session.session_type = EVALUATION`.
    - 真实行为评估使用正常的 Agent 应用路径并持久化其会话；这些记录会被保留用于诊断，并通过 `agent_session.session_type = EVALUATION` 明确标识。

These are explicit product boundaries, not claims that the missing capabilities are already solved.

这些是明确的产品边界，而不是宣称缺失的能力已经被解决。
