# NexusMind V3 Agent

V3 adds a controlled Agent layer on top of V1 ingestion/RAG and V2 retrieval. It uses real qwen3.5-flash function calling, two read-only knowledge tools, structured SSE, bounded conversation memory, and objective behavior evaluation.

## Architecture

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

## Fixed RAG vs Agent

V1 Fixed RAG follows a predetermined pipeline:

```text
Query → Retrieval → Context → LLM → Answer
```

V3 Agent starts with the model:

```text
Query → LLM → decide whether to use a tool
            → inspect tool result
            → decide the next step
            → final answer
```

Both remain available in the Web UI. Fixed RAG is the predictable grounded-answer path; Agent Chat demonstrates conditional tool selection, multi-step execution, and cross-request conversational context.

## Tools and Chaining

`search_knowledge_base` discovers relevant sources through the configured `RetrievalService`. `get_document_context` accepts only a `sourceId` previously returned during the current run and loads nearby chunks from the same document.

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

## Conversation Memory

```text
Session
├── Run 1
├── Run 2
└── Run 3
```

`sessionId` survives across HTTP requests while each request gets a new `runId`, deadline, counters, and SourceRegistry. MySQL stores only `USER` and final `ASSISTANT` messages. Tool calls, tool responses, invocation IDs, and execution trace do not enter cross-request memory. The prompt receives at most the latest 12 persisted messages by default. See [`agent-memory.md`](agent-memory.md) for transaction and failure semantics.

## Source Scope and Citation

`S1`, `S2`, and related IDs belong to one Agent Run and are not persisted as Session identity. Historical Assistant text is stored exactly as the user saw it, but stale `[S<number>]` markers are removed before injection into a later prompt. Only current-run tool sources are valid citations.

The Web UI parses citations as text segments, shows final Source cards, and never renders model output through `v-html`.

## Structured SSE and Tool Trace

The Agent endpoint emits `assistant_delta`, `tool_start`, `tool_result`, `tool_error`, `done`, and `error`. Every event contains the same `sessionId` and per-request `runId`. The browser displays observable tool lifecycle, safe arguments, result counts, durations, source metadata, tool-call count, model-turn count, and total duration. It never displays hidden reasoning or chain-of-thought.

## Guardrails

- `maxToolCalls = 5` per Agent Run.
- One absolute 30-second deadline covers all model turns and tools.
- Model-visible Tool Schemas expose only minimal parameters.
- `knowledgeBaseId` comes from server-side `ToolContext`.
- `sourceId` prevents arbitrary internal Chunk ID access.
- Context remains within the current KnowledgeBase and READY/INDEXED documents.
- Tool output and conversation history are untrusted data.
- Sessions are bound to one KnowledgeBase.
- Failed, timed-out, tool-limited, or uncommitted Runs do not write Memory.
- `done` means both final answer generation and Memory commit completed.

## Agent Behavior Evaluation

`evaluation/agent/agent-behavior.jsonl` contains 11 scenarios across DIRECT, SEARCH, MULTI_TOOL, INSUFFICIENT_KNOWLEDGE, MEMORY, and GUARDRAIL. The evaluator invokes the real `AgentChatService` and observes SSE events; it never implements search or context logic itself.

Metrics cover scenario count, completion rate, exact Tool sequence match, unexpected Tool calls, average Tool calls/model turns/duration, and Session continuity. It intentionally does not grade answer quality and does not use an LLM judge. Default tests use a deterministic fake executor.

### Real evaluation command

This command invokes qwen3.5-flash and must only be run explicitly with valid local configuration:

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

## Known Limitations

1. The backend does not coordinate concurrent requests within one Session.
2. There is no provider retry, fallback, or circuit breaker.
3. There is no Session list, title, rename, or delete API/UI.
4. Browser refresh loses visible UI history; MySQL memory remains.
5. The Memory window is message-count based, not token-aware.
6. There is no semantic or long-term user memory.
7. Final citations are prompt-constrained but not Java-side validated.
8. Browser/model/tool cancellation is best effort.
9. V3 has exactly two read-only knowledge tools.
10. Agent behavior evaluation is deliberately small and behavior-only.

These limitations are recorded for later engineering work; V3 does not pre-implement V4 mechanisms.
