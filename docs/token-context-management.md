# Token and Context Management

Checkpoint 18 introduces an application-owned token budget for RAG prompts, Agent conversation
memory, and Agent tool results. This is deliberately smaller than the provider's advertised context
window: a model being able to accept a very large prompt does not make that prompt predictable in
latency, cost, memory use, or network size.

## Estimation and global policy

NexusMind wraps Spring AI 2.0.1's `TokenCountEstimator` and
`JTokkitTokenCountEstimator` behind `NexusTokenEstimator`. No additional tokenizer dependency or
remote token-count request is used. The result is always called `estimatedTokens`: JTokkit's
encoding is not Qwen's exact tokenizer, and provider-specific chat serialization adds overhead.

The baseline application policy is:

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

## RAG context

`nexusmind.rag.context.max-tokens=12000` is a ceiling, not an unconditional allocation. The actual
context budget is the lower of that ceiling and the global input budget remaining after the system
prompt, current question, and fixed prompt formatting are estimated.

Retrieved sources are evaluated as their final formatted blocks, including source ID, file, page,
section, chunk ID, score, separators, and content. Sources are included in retrieval-rank order as a
prefix. If S1 and S2 fit but S3 does not, processing stops; a shorter S4 is not substituted. If S1
alone is oversized, only its content is deterministically prefix-truncated and marked
`… [truncated]`; its source metadata and citation ID remain available. If even fixed prompt or source
metadata cannot fit, RAG returns `RAG_CONTEXT_BUDGET_EXCEEDED` without invoking the provider.

`RagContext` owns both the formatted prompt content and `includedHits`/sources, so SSE source
metadata contains only passages the model actually saw. Retrieval TopK, ranking, and V2 evaluation
algorithms are unchanged.

## Agent conversation memory

Memory keeps two independent bounds:

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

The token window affects prompt selection only: database messages are never deleted. The system
policy and current user message are never silently truncated. Their fixed content dynamically
clamps the memory budget; if they already exceed the global policy, Agent returns
`AGENT_CONTEXT_BUDGET_EXCEEDED` without calling the model.

## Agent tool results

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

`search_knowledge_base` preserves retrieval order. It includes a rank prefix, truncating only the
first item when that item alone is oversized. Omitted hits are not registered in
`AgentSourceRegistry`, so `done.sources` remains aligned with model-visible sources.

`get_document_context` gives the requested target highest priority. It truncates the target when
necessary, then considers neighbors by distance and stable chunk-index tie-break, and finally emits
included items in chunk-index reading order. Tool results expose `truncated`, `omittedItemCount`, and
`estimatedTokens`; they do not expose internal global limits. The prompt states that repeating the
same tool call normally does not reveal omitted content.

## Final validation and lifecycle interactions

Before every Agent model turn, NexusMind estimates the complete current message history—including
assistant tool calls and tool responses—and checks it against the effective Agent message budget.
Current-run tool protocol messages are never independently deleted or rewritten. If pre-budgeting
is insufficient, the run fails clearly rather than sending an invalid or oversized history.

Provider retry receives the same already-budgeted prompt; retry attempts do not reselect memory or
change the tool budget. A budget error follows the normal Agent terminal path and releases the CP17
session lease. It writes no conversation turn and never emits `done`.

## Why there is no memory summarization

This checkpoint uses deterministic recent-turn selection. LLM summarization would add another
provider call, new failure and injection boundaries, and lossy state whose quality requires separate
evaluation. Chunking, Retrieval TopK, reranker candidate depth, and the V2 retrieval algorithms are
also intentionally unchanged.

## Development-only small-budget check

Temporarily override budgets at startup; do not commit these small values as production defaults:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--nexusmind.ai.context.max-context-tokens=4096 --nexusmind.ai.context.reserved-output-tokens=512 --nexusmind.ai.context.safety-margin-tokens=512 --nexusmind.ai.context.tool-definition-reserve-tokens=256 --nexusmind.agent.memory.max-tokens=600 --nexusmind.agent.tool-result.max-tokens-per-call=700 --nexusmind.agent.tool-result.max-tokens-per-run=1200"
```

Use a long existing session and a knowledge question with several large passages. Verify that old
complete memory turns disappear first, tool items report truncation/omission, or the request returns
the explicit budget error. Normal small Direct, Search, Multi-Tool, Memory, and RAG flows should be
unchanged with the baseline settings.

## Known limitations

- Token estimates are approximate for Qwen; no exact provider tokenizer or remote count call exists.
- Memory is not summarized and there is no semantic or long-term memory.
- Tool protocol history is not pruned after execution; final validation fails if it still grows too large.
- There is no circuit breaker, alternate-provider fallback, token usage persistence, metrics, or tracing.
- Browser/provider cancellation remains best effort.
- Historical package-structure and P2 cleanup remains deferred.
