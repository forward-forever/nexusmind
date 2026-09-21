# Token and Context Management（Token 与上下文管理）

Checkpoint 18 introduces an application-owned token budget for RAG prompts, Agent conversation
memory, and Agent tool results. This is deliberately smaller than the provider's advertised context
window: a model being able to accept a very large prompt does not make that prompt predictable in
latency, cost, memory use, or network size.

Checkpoint 18 引入了应用自有的 token 预算，覆盖 RAG 提示词、Agent 会话记忆和 Agent 工具结果。这个预算刻意小于模型提供商宣称的上下文窗口：模型能接受非常大的提示词，并不意味着该提示词在延迟、成本、内存占用或网络传输上是可预测的。

## Estimation and global policy（估算与全局策略）

NexusMind wraps Spring AI 2.0.1's `TokenCountEstimator` and
`JTokkitTokenCountEstimator` behind `NexusTokenEstimator`. No additional tokenizer dependency or
remote token-count request is used. The result is always called `estimatedTokens`: JTokkit's
encoding is not Qwen's exact tokenizer, and provider-specific chat serialization adds overhead.

NexusMind 将 Spring AI 2.0.1 的 `TokenCountEstimator` 和 `JTokkitTokenCountEstimator` 封装在 `NexusTokenEstimator` 之后。不引入额外的 tokenizer 依赖，也不发起远程 token 计数请求。结果统一称为 `estimatedTokens`：JTokkit 的编码并非 Qwen 的精确 tokenizer，而且特定于提供商的对话序列化还会带来额外开销。

The baseline application policy is:

基线应用策略如下：

```yaml
nexusmind:
  ai:
    context:
      max-context-tokens: 32768
      reserved-output-tokens: 4096
      safety-margin-tokens: 4096
      tool-definition-reserve-tokens: 2048
```

The output and safety reserves leave 24,576 estimated input tokens for RAG messages. Agent messages
also reserve 2,048 tokens for tool definitions, leaving 22,528. Startup fails if the limits are
negative or their reserves leave no prompt capacity. The safety margin accounts for tokenizer
mismatch and serialization overhead. When the configured output reserve is positive, it is also
sent as the Spring AI `maxTokens` model option.

输出预留和安全边际为 RAG 消息留出 24,576 个估算输入 token。Agent 消息还预留 2,048 个 token 给工具定义，剩下 22,528。如果限制为负数、或各项预留后没有任何提示词容量，启动将直接失败。安全边际用于抵消 tokenizer 不一致和序列化开销。当配置的输出预留为正数时，它还会作为 Spring AI 的 `maxTokens` 模型选项发送给模型。

## RAG context（RAG 上下文）

`nexusmind.rag.context.max-tokens=12000` is a ceiling, not an unconditional allocation. The actual
context budget is the lower of that ceiling and the global input budget remaining after the system
prompt, current question, and fixed prompt formatting are estimated.

`nexusmind.rag.context.max-tokens=12000` 是一个上限，而不是无条件分配。实际上下文预算取两者中的较小值：这个上限，以及估算完系统提示词、当前问题和固定提示词格式之后剩余的全局输入预算。

Retrieved sources are evaluated as their final formatted blocks, including source ID, file, page,
section, chunk ID, score, separators, and content. Sources are included in retrieval-rank order as a
prefix. If S1 and S2 fit but S3 does not, processing stops; a shorter S4 is not substituted. If S1
alone is oversized, only its content is deterministically prefix-truncated and marked
`… [truncated]`; its source metadata and citation ID remain available. If even fixed prompt or source
metadata cannot fit, RAG returns `RAG_CONTEXT_BUDGET_EXCEEDED` without invoking the provider.

检索到的来源以其最终格式化后的文本块参与估算，包括来源 ID、文件、页码、章节、chunk ID、分数、分隔符和内容。来源按检索排名顺序以前缀方式依次纳入：如果 S1 和 S2 放得下而 S3 放不下，处理即停止；不会跳过 S3 去替换更短的 S4。如果仅 S1 一条就超限，则只对其内容做确定性的前缀截断并标记 `… [truncated]`；其来源元数据和引用 ID 仍然保留。如果连固定提示词或来源元数据都放不下，RAG 直接返回 `RAG_CONTEXT_BUDGET_EXCEEDED`，不调用模型提供商。

`RagContext` owns both the formatted prompt content and `includedHits`/sources, so SSE source
metadata contains only passages the model actually saw. Retrieval TopK, ranking, and V2 evaluation
algorithms are unchanged.


## Agent conversation memory（Agent 会话记忆）
`RagContext` 同时持有格式化后的提示词内容和 `includedHits`/sources，因此 SSE 的来源元数据只包含模型真正看到的段落。检索 TopK、排序和 V2 评估算法保持不变。

Memory keeps two independent bounds:

记忆设有两个相互独立的限制：

```yaml
nexusmind:
  agent:
    memory:
      max-messages: 12
      max-tokens: 6000
```

The database query still reads only the newest bounded set. Historical citations such as `[S1]`
are removed before estimation because the sanitized text is what the next model turn sees. When the
token limit is reached, complete oldest `USER + ASSISTANT` turns are evicted and the most recent
complete suffix is retained. An oversized historical turn is dropped rather than truncated.

数据库查询仍然只读取最新的有界集合。像 `[S1]` 这样的历史引用标记会在估算前移除，因为清洗后的文本才是下一轮模型真正看到的内容。当达到 token 限制时，最旧的完整 `USER + ASSISTANT` 轮次会被逐出，保留最近一段完整的后缀。超大的历史轮次会被整轮丢弃，而不是截断。

The token window affects prompt selection only: database messages are never deleted. The system
policy and current user message are never silently truncated. Their fixed content dynamically
clamps the memory budget; if they already exceed the global policy, Agent returns
`AGENT_CONTEXT_BUDGET_EXCEEDED` without calling the model.

token 窗口只影响提示词的选择：数据库中的消息永远不会被删除。系统策略和当前用户消息永远不会被静默截断。它们的固定内容会动态压缩记忆预算；如果它们本身就超出全局策略，Agent 直接返回 `AGENT_CONTEXT_BUDGET_EXCEEDED`，不调用模型。

## Agent tool results（Agent 工具结果）

```yaml
nexusmind:
  agent:
    tool-result:
      max-tokens-per-call: 5000
      max-tokens-per-run: 12000
```

The run-scoped `AgentRunTokenBudget` accounts for the estimated final serialized JSON returned to
the model. Allocation is synchronized because one model response may request multiple tools. The
per-call bound prevents one result from dominating the prompt; the cumulative bound prevents five
individually valid results from overflowing the run.

run 级别的 `AgentRunTokenBudget` 记账对象是返回给模型的最终序列化 JSON 的估算值。分配过程加锁（synchronized），因为一次模型回复可能同时请求多个工具。单次上限防止单个结果霸占提示词；累计上限防止五个各自合法的结果把整个 run 撑爆。

`search_knowledge_base` preserves retrieval order. It includes a rank prefix, truncating only the
first item when that item alone is oversized. Omitted hits are not registered in
`AgentSourceRegistry`, so `done.sources` remains aligned with model-visible sources.

`search_knowledge_base` 保持检索顺序。它带排名前缀，只有当第一条单独超限时才截断该条。被省略的命中不会注册进 `AgentSourceRegistry`，因此 `done.sources` 始终与模型可见的来源保持一致。

`get_document_context` gives the requested target highest priority. It truncates the target when
necessary, then considers neighbors by distance and stable chunk-index tie-break, and finally emits
included items in chunk-index reading order. Tool results expose `truncated`, `omittedItemCount`, and
`estimatedTokens`; they do not expose internal global limits. The prompt states that repeating the
same tool call normally does not reveal omitted content.

`get_document_context` 给请求的目标 chunk 最高优先级。必要时截断目标，然后按距离纳入相邻 chunk（并用 chunk-index 做稳定平局裁决），最后按 chunk-index 阅读顺序输出被纳入的条目。工具结果暴露 `truncated`、`omittedItemCount` 和 `estimatedTokens`；不暴露内部全局限制。提示词中声明：重复相同的工具调用通常不会揭示被省略的内容。

## Final validation and lifecycle interactions（最终校验与生命周期交互）

Before every Agent model turn, NexusMind estimates the complete current message history—including
assistant tool calls and tool responses—and checks it against the effective Agent message budget.
Current-run tool protocol messages are never independently deleted or rewritten. If pre-budgeting
is insufficient, the run fails clearly rather than sending an invalid or oversized history.

在每次 Agent 模型轮次之前，NexusMind 都会估算完整的当前消息历史——包括 assistant 的工具调用和工具响应——并对照有效的 Agent 消息预算进行校验。当前 run 的工具协议消息永远不会被单独删除或重写。如果预算规划不足，run 会明确失败，而不是发送非法或超大的历史。

Provider retry receives the same already-budgeted prompt; retry attempts do not reselect memory or
change the tool budget. A budget error follows the normal Agent terminal path and releases the CP17
session lease. It writes no conversation turn and never emits `done`.

提供商重试收到的是同一个已完成预算的提示词；重试不会重新选择记忆，也不会改变工具预算。预算错误走正常的 Agent 终止路径并释放 CP17 会话租约。它不写任何对话轮次，也永远不会发出 `done`。

## Why there is no memory summarization（为什么没有记忆摘要）

This checkpoint uses deterministic recent-turn selection. LLM summarization would add another
provider call, new failure and injection boundaries, and lossy state whose quality requires separate
evaluation. Chunking, Retrieval TopK, reranker candidate depth, and the V2 retrieval algorithms are
also intentionally unchanged.

本 Checkpoint 采用确定性的最近轮次选择。LLM 摘要会带来额外的提供商调用、新的故障面和注入边界，以及需要单独评估质量的有损状态。切分、检索 TopK、reranker 候选深度和 V2 检索算法也都刻意保持不变。

## Development-only small-budget check（仅开发用的小预算检查）

Temporarily override budgets at startup; do not commit these small values as production defaults:

启动时临时覆盖预算；不要把下面这些小值作为生产默认值提交：

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--nexusmind.ai.context.max-context-tokens=4096 --nexusmind.ai.context.reserved-output-tokens=512 --nexusmind.ai.context.safety-margin-tokens=512 --nexusmind.ai.context.tool-definition-reserve-tokens=256 --nexusmind.agent.memory.max-tokens=600 --nexusmind.agent.tool-result.max-tokens-per-call=700 --nexusmind.agent.tool-result.max-tokens-per-run=1200"
```

Use a long existing session and a knowledge question with several large passages. Verify that old
complete memory turns disappear first, tool items report truncation/omission, or the request returns
the explicit budget error. Normal small Direct, Search, Multi-Tool, Memory, and RAG flows should be
unchanged with the baseline settings.

使用一个已有较长历史的 session，并提一个涉及多个大段落的知识问题。验证：旧的完整记忆轮次最先消失、工具条目报告截断/省略，或者请求返回明确的预算错误。在基线配置下，正常的 Direct、Search、Multi-Tool、Memory 和 RAG 小流量流程应保持不变。

## Known limitations（已知局限）

- Token estimates are approximate for Qwen; no exact provider tokenizer or remote count call exists.
  - 对 Qwen 的 token 估算只是近似；没有精确的提供商 tokenizer，也没有远程计数调用。
- Memory is not summarized and there is no semantic or long-term memory.
  - 记忆不做摘要，没有语义记忆或长期记忆。
- Tool protocol history is not pruned after execution; final validation fails if it still grows too large.
  - 工具协议历史在执行后不会被裁剪；如果它仍然增长过大，最终校验会失败。
- There is no circuit breaker, alternate-provider fallback, token usage persistence, metrics, or tracing.
  - 没有熔断器、备用提供商回退、token 用量持久化、指标或链路追踪。
- Browser/provider cancellation remains best effort.
  - 浏览器/提供商的取消仍然只是尽力而为。
- Historical package-structure and P2 cleanup remains deferred.
  - 历史遗留的包结构整理和 P2 清理仍然被推迟。

## Summary: roles and levels（总结：作用与层级）

All token configuration nests into three layers: one global pool, per-feature ceilings, and one
run-scoped ledger. The global policy sets the total window; each feature ceiling only caps its own
share; the actual allocation is always the smaller of the ceiling and the remaining global budget.

所有 token 配置嵌套为三层：一个全局水池、若干功能上限、一个 run 级别的账本。全局策略定总量窗口，功能上限只限制各自份额，实际额度永远是「上限与全局剩余」取小。

```text
nexusmind.ai.context                           global pool (per model request)
  max-context-tokens: 32768                    total window
  ├─ reserved-output-tokens: 4096              model output reserve (also the maxTokens option)
  ├─ safety-margin-tokens: 4096                tokenizer mismatch + serialization overhead
  ├─ tool-definition-reserve-tokens: 2048      tool definitions (Agent only)
  └─ remaining input budget, shared by
      ├─ RAG context      ceiling 12000 (rag.context.max-tokens)
      ├─ Agent memory     ceiling 6000 (agent.memory.max-tokens) + count cap 12
      └─ tool results     per-call ≤ 5000, per-run ≤ 12000 (agent.tool-result.*)
```

Baseline arithmetic:

基线配置下的算术：

```text
32768 - 4096 (output) - 4096 (safety) = 24576   RAG message input budget
24576 - 2048 (tool definitions)        = 22528   Agent message input budget
```

`max-context-tokens` is a window per model request, not a session quota. Every model turn is
validated against the effective input budget, so a long session may spend the window many times
over; how many turns a session keeps in MySQL is bounded by nothing token-related.

`max-context-tokens` 是单次模型请求的窗口，不是会话配额。每个模型轮次都会对照有效输入预算校验，因此一个长会话可以反复花掉这个窗口；会话在 MySQL 中保留多少轮次，不受任何 token 相关限制。

The lifecycle levels referenced below nest as:

下面表格引用的生命周期层级嵌套关系如下：

```text
session ─ (one conversation, long-lived; 一次会话，长期)
  └─ run ─ (one user message triggers full processing, may contain multiple model turns;
            一条用户消息触发的完整处理，含多轮模型调用)
      └─ turn ─ (one model request inside a run; a run may have several turns because of
                 tool loops; run 内的一次模型请求；run 可能有多个 turn，因为工具循环)
          └─ call ─ (one tool invocation; 一次工具调用)
```

Config placement by lifecycle level:
各配置按生命周期层级划分：

| Level | Config | Role |
|---|---|---|
| session | — | history is stored but never deleted or token-limited |
| run | `memory.max-messages` / `max-tokens` | history selected once per user message |
| run | `tool-result.max-tokens-per-run` | cumulative ledger across all tool calls in the run |
| turn | `ai.context.*` | validated before every model call |
| turn | `rag.context.max-tokens` | computed once per RAG request |
| call | `tool-result.max-tokens-per-call` | ceiling for a single tool result |

| 层级 | 配置 | 作用 |
|---|---|---|
| session | — | 历史只存不删，不受 token 限制 |
| run | `memory.max-messages` / `max-tokens` | 每条用户消息选一次历史进 prompt |
| run | `tool-result.max-tokens-per-run` | run 内所有工具结果的累计账本 |
| turn | `ai.context.*` | 每次模型调用前校验 |
| turn | `rag.context.max-tokens` | 每次 RAG 请求组装时计算 |
| call | `tool-result.max-tokens-per-call` | 单个工具结果的上限 |

In one sentence: the global pool sets the window, feature ceilings bound their own share, the
tool-result ledger meters a run cumulatively, and the per-turn final validation catches whatever
static planning misses.

一句话总结：全局水池定窗口，功能上限管份额，工具结果账本按 run 累计计量，每轮的最终校验兜住静态规划漏掉的部分。
