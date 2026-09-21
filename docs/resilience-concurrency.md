# Resilience and Concurrency（韧性与并发）

Checkpoint 17 adds bounded model-provider retry, parallel Hybrid retrieval routes, and a durable
MySQL lease for Agent sessions. It does not add a circuit breaker, provider fallback, distributed
cache, or observability framework.

Checkpoint 17 新增了有界的模型提供商重试、并行的混合检索各路，以及面向 Agent 会话的持久化 MySQL 租约。它没有引入熔断器、提供商回退、分布式缓存或可观测性框架。

## Provider retry（提供商重试）

NexusMind uses Spring Framework 7's `org.springframework.core.retry.RetryTemplate` and
`RetryPolicy`; no resilience library was added. The baseline is an initial attempt plus at most two
retries, exponential delay from 200 ms, multiplier 2, maximum delay 2 seconds, and up to 100 ms of
jitter.

NexusMind 使用 Spring Framework 7 的 `org.springframework.core.retry.RetryTemplate` 和 `RetryPolicy`；没有引入任何韧性库。基线配置是首次尝试加最多两次重试，指数延迟从 200 ms 起，乘数为 2，最大延迟 2 秒，抖动最多 100 ms。

Failures are deliberately classified before retry:

重试之前会对失败做刻意的分类：

- retryable: network I/O/timeouts/resets, HTTP 408, HTTP 429, and HTTP 5xx;
  - 可重试：网络 I/O/超时/连接重置、HTTP 408、HTTP 429 和 HTTP 5xx；
- non-retryable: HTTP 400/401/403/404, invalid request or response data, validation errors, and
  unsupported configuration.
  - 不可重试：HTTP 400/401/403/404、非法的请求或响应数据、校验错误，以及不支持的配置。

Embedding retries one unchanged batch. It never falls back to a different embedding model because
mixing vector spaces would corrupt the Milvus projection. Rerank retries the same query, candidate
list, and `topN`; exhaustion still fails `HYBRID_RERANK` instead of pretending that upstream RRF was
reranked.

embedding 重试的是同一个未变更的批次。它绝不会回退到另一个 embedding 模型，因为混合向量空间会污染 Milvus 投影。rerank 重试的是同一个查询、同一个候选列表和同一个 `topN`；即使重试耗尽，也仍然以 `HYBRID_RERANK` 失败，而不是假装上游的 RRF 结果已经过 rerank。

### Streaming boundary（流式边界）

Streaming is retried only before an observable side effect. A RAG model turn may retry before its
first assistant delta. An Agent model turn may retry before it emits an assistant delta or begins
tool execution. After output is visible, the turn fails rather than replaying text and producing a
duplicated answer. Tool execution itself is outside provider retry.

流式调用只在出现可观察的副作用之前才重试。RAG 的模型轮次可以在首个 assistant delta 之前重试。Agent 的模型轮次可以在发出 assistant delta 或开始工具执行之前重试。一旦输出已经可见，该轮次就失败，而不是重放文本并产生重复的回答。工具执行本身不在提供商重试的范围内。

All Agent attempts consume the same absolute 30-second run deadline. A retry delay that does not fit
inside the remaining deadline is rejected as `AGENT_TIMEOUT`. Infrastructure retry attempts do not
increment the logical model-turn count. Logs contain provider, operation, attempt, classification,
HTTP status when available, and delay; they exclude credentials, prompts, queries, and chunk text.

所有 Agent 尝试都消耗同一个绝对的 30 秒 run 截止时间。如果某次重试延迟无法塞进剩余的截止时间，就会以 `AGENT_TIMEOUT` 被拒绝。基础设施层面的重试尝试不会增加逻辑上的模型轮次数。日志包含提供商、操作、尝试次数、分类、可获取时的 HTTP 状态和延迟；不包含凭证、prompt、查询和 chunk 文本。

## Parallel Hybrid retrieval（并行混合检索）

Dense and BM25 route calls are submitted together to a dedicated bounded
`ThreadPoolTaskExecutor`:

Dense 和 BM25 两路调用会一起提交到一个专用的有界 `ThreadPoolTaskExecutor`：

```text
          ┌─ Dense → visibility validation ─┐
Query ────┤                                ├─ Application RRF
          └─ BM25  → visibility validation ─┘
```

The default pool is 4/4 threads with a queue capacity of 50 and an abort rejection policy. It never
uses the common fork-join pool. Candidate depth, visibility rules, RRF `k`, scores, contributions,
and deterministic tie-breaks are unchanged. An empty route is valid; an exception in either route
still fails the whole Hybrid request. Cancellation of the other future is best effort.

默认线程池是 4/4 线程，队列容量为 50，拒绝策略为 abort。它从不使用公共的 fork-join 线程池。候选深度、可见性规则、RRF 的 `k`、分数、贡献值和确定性的平局裁决都保持不变。某一路返回空结果是合法的；任意一路抛异常仍然会让整个 Hybrid 请求失败。对另一路 future 的取消是尽力而为。

## Agent session database lease（Agent 会话的数据库租约）

`agent_session` stores `active_run_id`, `run_acquired_at`, and `run_lease_until`. The request `runId`
is the lease owner and fencing token. Existing sessions are acquired by one short atomic update:

`agent_session` 存储 `active_run_id`、`run_acquired_at` 和 `run_lease_until`。请求中的 `runId` 既是租约持有者，也是围栏 token。已存在的会话通过一次短暂的原子更新来获取：

```text
active_run_id is null OR run_lease_until < CURRENT_TIMESTAMP(3)
```

Lease timestamps use MySQL time to avoid cross-instance application-clock skew. A new session is
inserted already owned by its creating run. The default lease is 45 seconds and startup validation
requires it to exceed the 30-second absolute Agent duration. No long transaction or session
heartbeat is held while the model runs.

租约时间戳使用 MySQL 时间，以避免跨实例的应用时钟偏差。新会话在插入时就已经归创建它的 run 所有。默认租约是 45 秒，启动校验要求它大于 Agent 绝对的 30 秒执行时长。模型运行期间不持有长事务，也不发送会话心跳。

A fresh owner causes concurrent requests for the same session to receive
`AGENT_SESSION_BUSY` before any model call. Different sessions remain independent. Normal completion,
error, and cancellation release with `WHERE active_run_id = :runId`; an old run cannot clear a newer
owner. A crashed process leaves a lease that naturally expires.

当出现新的租约持有者时，针对同一会话的并发请求会在任何模型调用之前收到 `AGENT_SESSION_BUSY`。不同会话之间彼此独立。正常完成、出错和取消时，通过 `WHERE active_run_id = :runId` 释放租约；旧的 run 无法清除更新的持有者。进程崩溃会留下一个自然过期的租约。

Before committing the final user/assistant memory pair, the transaction locks the session row and
validates both the current `runId` and an unexpired lease using database time. A resumed old run that
has lost ownership emits `AGENT_SESSION_LEASE_LOST`, writes no messages, and never emits `done`.

在提交最后一条 user/assistant 记忆对之前，事务会锁定会话行，并使用数据库时间同时校验当前 `runId` 和未过期的租约。一个失去所有权后又恢复执行的旧 run 会发出 `AGENT_SESSION_LEASE_LOST`，不写入任何消息，也永远不会发出 `done`。

## Current limitations（当前局限）

- No circuit breaker or alternate-provider fallback.
  - 没有熔断器，也没有备用提供商回退。
- Cancellation and cross-route future cancellation remain best effort.
  - 取消操作和跨路 future 的取消仍然只是尽力而为。
- Token-aware context budgeting is implemented in CP18; metrics, tracing, and dashboards remain absent.
  - 具备 token 感知的上下文预算已在 CP18 实现；指标、链路追踪和看板仍然缺失。
- The UI does not restore an Agent session after a browser refresh.
  - 浏览器刷新后，UI 不会恢复 Agent 会话。
- Historical package-structure and P2 cleanup remains deferred to V4 final cleanup.
  - 历史遗留的包结构整理和 P2 清理仍然推迟到 V4 最终清理。
