# Resilience and Concurrency

Checkpoint 17 adds bounded model-provider retry, parallel Hybrid retrieval routes, and a durable
MySQL lease for Agent sessions. It does not add a circuit breaker, provider fallback, distributed
cache, or observability framework.

## Provider retry

NexusMind uses Spring Framework 7's `org.springframework.core.retry.RetryTemplate` and
`RetryPolicy`; no resilience library was added. The baseline is an initial attempt plus at most two
retries, exponential delay from 200 ms, multiplier 2, maximum delay 2 seconds, and up to 100 ms of
jitter.

Failures are deliberately classified before retry:

- retryable: network I/O/timeouts/resets, HTTP 408, HTTP 429, and HTTP 5xx;
- non-retryable: HTTP 400/401/403/404, invalid request or response data, validation errors, and
  unsupported configuration.

Embedding retries one unchanged batch. It never falls back to a different embedding model because
mixing vector spaces would corrupt the Milvus projection. Rerank retries the same query, candidate
list, and `topN`; exhaustion still fails `HYBRID_RERANK` instead of pretending that upstream RRF was
reranked.

### Streaming boundary

Streaming is retried only before an observable side effect. A RAG model turn may retry before its
first assistant delta. An Agent model turn may retry before it emits an assistant delta or begins
tool execution. After output is visible, the turn fails rather than replaying text and producing a
duplicated answer. Tool execution itself is outside provider retry.

All Agent attempts consume the same absolute 30-second run deadline. A retry delay that does not fit
inside the remaining deadline is rejected as `AGENT_TIMEOUT`. Infrastructure retry attempts do not
increment the logical model-turn count. Logs contain provider, operation, attempt, classification,
HTTP status when available, and delay; they exclude credentials, prompts, queries, and chunk text.

## Parallel Hybrid retrieval

Dense and BM25 route calls are submitted together to a dedicated bounded
`ThreadPoolTaskExecutor`:

```text
          ┌─ Dense → visibility validation ─┐
Query ────┤                                ├─ Application RRF
          └─ BM25  → visibility validation ─┘
```

The default pool is 4/4 threads with a queue capacity of 50 and an abort rejection policy. It never
uses the common fork-join pool. Candidate depth, visibility rules, RRF `k`, scores, contributions,
and deterministic tie-breaks are unchanged. An empty route is valid; an exception in either route
still fails the whole Hybrid request. Cancellation of the other future is best effort.

## Agent session database lease

`agent_session` stores `active_run_id`, `run_acquired_at`, and `run_lease_until`. The request `runId`
is the lease owner and fencing token. Existing sessions are acquired by one short atomic update:

```text
active_run_id is null OR run_lease_until < CURRENT_TIMESTAMP(3)
```

Lease timestamps use MySQL time to avoid cross-instance application-clock skew. A new session is
inserted already owned by its creating run. The default lease is 45 seconds and startup validation
requires it to exceed the 30-second absolute Agent duration. No long transaction or session
heartbeat is held while the model runs.

A fresh owner causes concurrent requests for the same session to receive
`AGENT_SESSION_BUSY` before any model call. Different sessions remain independent. Normal completion,
error, and cancellation release with `WHERE active_run_id = :runId`; an old run cannot clear a newer
owner. A crashed process leaves a lease that naturally expires.

Before committing the final user/assistant memory pair, the transaction locks the session row and
validates both the current `runId` and an unexpired lease using database time. A resumed old run that
has lost ownership emits `AGENT_SESSION_LEASE_LOST`, writes no messages, and never emits `done`.

## Current limitations

- No circuit breaker or alternate-provider fallback.
- Cancellation and cross-route future cancellation remain best effort.
- No token-aware context budget, metrics, tracing, or dashboard.
- The UI does not restore an Agent session after a browser refresh.
- Historical package-structure and P2 cleanup remains deferred to V4 final cleanup.
