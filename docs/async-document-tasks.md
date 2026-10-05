# Durable asynchronous document tasks（持久化的异步文档任务）

Checkpoint 16 moves document processing and indexing out of the HTTP request lifecycle. The API now commits a task to MySQL and returns immediately; a bounded background worker claims and executes that durable task.

Checkpoint 16 把文档处理和索引移出 HTTP 请求生命周期。API 现在把任务提交到 MySQL 后立即返回；由一个带并发上限的后台 worker 认领并执行这个持久化任务。

```text
HTTP
  ↓
DocumentTaskService
  ↓
MySQL knowledge_document_task
  ↓
PENDING
  ↓ atomic claim
RUNNING
  ↓
┌──────────────┬─────────────────────┐
│ PROCESS      │ INDEX               │
│ Parse/Chunk  │ Embedding + Milvus  │
│ MySQL        │ projection          │
└──────────────┴─────────────────────┘
  ↓
SUCCEEDED / FAILED
```

## Why MySQL instead of a message broker（为什么用 MySQL 而不是消息中间件）

NexusMind is currently a single deployable service and already requires MySQL for document and chunk state. A MySQL-backed task row gives the application durable acceptance, row locking, atomic claiming, uniqueness, inspection, and recovery without introducing Kafka, RabbitMQ, or a separate workflow service. The task table is execution-coordination state; `knowledge_document` and `knowledge_chunk` remain the business source of truth, while Milvus remains a derived retrieval projection.

NexusMind 目前是单一可部署服务，本来就需要 MySQL 来存储文档和 chunk 状态。以 MySQL 为后端的任务行让应用获得持久化的受理、行锁、原子认领、唯一性约束、可检视性和恢复能力，而无需引入 Kafka、RabbitMQ 或独立的工作流服务。任务表属于执行协调状态；`knowledge_document` 和 `knowledge_chunk` 仍是业务事实来源，Milvus 仍是派生的检索投影。

An in-memory queue, application event, or fire-and-forget future would lose accepted work on a JVM crash. The API therefore reports success only after the task enqueue transaction commits.

内存队列、应用事件或 fire-and-forget 的 future 都会在 JVM 崩溃时丢失已受理的工作。因此 API 只有在任务入队事务提交之后才报告成功。

## State machine and logical identity（状态机与逻辑身份）

```text
                 explicit retry
              ┌──────────────────┐
              ↓                  │
PENDING → RUNNING → SUCCEEDED    FAILED
             │                    ↑
             └────────────────────┘

stale RUNNING → reconcile business state
              → SUCCEEDED / FAILED / PENDING
```

The unique key `(document_id, task_type)` defines one logical `PROCESS` task and one logical `INDEX` task per document. A failed retry reuses the same row, resets `recovery_count`, and retains the cumulative `attempt_count`; NexusMind does not create an unbounded task-history table.

唯一键 `(document_id, task_type)` 为每个文档定义了一个逻辑 `PROCESS` 任务和一个逻辑 `INDEX` 任务。失败后的重试复用同一行，重置 `recovery_count`，并保留累计的 `attempt_count`；NexusMind 不会创建无上限的任务历史表。

Enqueue operations use `knowledge_document SELECT ... FOR UPDATE` as their serialization point and the unique task key as a second line of defense. A repeated request returns the same active task. Enqueueing does not prematurely set `PROCESSING` or `INDEXING`: those states begin only when a worker actually executes the business service.

入队操作以 `knowledge_document SELECT ... FOR UPDATE` 作为串行化点，并以任务唯一键作为第二道防线。重复请求返回同一个活跃任务。入队不会提前把状态置为 `PROCESSING` 或 `INDEXING`：这些状态只有在 worker 真正执行业务服务时才开始。

## Claiming and ownership fencing（认领与所有权围栏）

Workers reserve a local executor slot before claiming. The short claim transaction uses `FOR UPDATE SKIP LOCKED`, changes one `PENDING` row to `RUNNING`, increments `attempt_count`, and assigns:

worker 在认领之前先预留本地的执行器槽位。短暂的认领事务使用 `FOR UPDATE SKIP LOCKED`，把一行 `PENDING` 改为 `RUNNING`，递增 `attempt_count`，并赋予：

- an instance-level `worker_id` for diagnostics;
  - 实例级别的 `worker_id`，用于诊断；
- a fresh per-attempt UUID `run_token`.
  - 每次尝试都生成一个全新的 UUID `run_token`。

Heartbeat, success, and failure writes require `id`, `RUNNING`, and the current `run_token`. If an old worker resumes after recovery and a new worker has claimed the task, the old token updates zero rows and cannot overwrite the new task owner. This is task-row fencing, not a guarantee that an old process cannot already have made an external side effect.

心跳、成功和失败的写入都要求匹配 `id`、`RUNNING` 和当前 `run_token`。如果旧 worker 在恢复之后重新苏醒，而新 worker 已经认领了该任务，旧 token 会更新零行，无法覆盖新的任务所有者。这是任务行级别的围栏，并不保证旧进程此前一定没有产生外部副作用。

The dedicated executor defaults to two workers and has no backlog for claimed tasks. Shutdown stops new claims and allows running tasks a bounded completion window. Work killed after that window remains `RUNNING` and is handled by stale recovery after restart.

专用执行器默认两个 worker，对已认领的任务没有积压。关闭时会停止新的认领，并给正在运行的任务一段有界的完成窗口。超过该窗口后被终止的工作仍保持 `RUNNING`，由重启后的过期恢复处理。

## Heartbeat and crash recovery（心跳与崩溃恢复）

The defaults are a 10-second heartbeat, a five-minute stale threshold, and a 30-second recovery scan. `stale-after` must be greater than `heartbeat-interval`. Recovery runs once after application startup and periodically thereafter.

默认值是 10 秒心跳、5 分钟过期阈值和 30 秒恢复扫描。`stale-after` 必须大于 `heartbeat-interval`。恢复在应用启动后运行一次，之后周期性运行。

```text
RUNNING
  ↓ heartbeat becomes stale
Recovery locks Document → Task and rechecks staleness
  ↓
business-state reconciliation
  ↓
SUCCEEDED / FAILED / PENDING
```

Reconciliation trusts the durable document state before deciding to repeat work:

对账在决定是否重做工作之前，先信任持久化的文档状态：

| Task | Business state | Recovery result |
|---|---|---|
| PROCESS | `READY` | task `SUCCEEDED`; do not parse again |
| PROCESS | `FAILED` | task `FAILED`; do not auto-retry |
| PROCESS | `PROCESSING` or retryable pre-state | document `UPLOADED`, task `PENDING` |
| INDEX | `INDEXED` | task `SUCCEEDED`; do not embed again |
| INDEX | index `FAILED` | task `FAILED`; do not auto-retry |
| INDEX | `INDEXING` or retryable pre-state | index `NOT_INDEXED`, task `PENDING` |

| 任务 | 业务状态 | 恢复结果 |
|---|---|---|
| PROCESS | `READY` | 任务 `SUCCEEDED`；不再重新解析 |
| PROCESS | `FAILED` | 任务 `FAILED`；不自动重试 |
| PROCESS | `PROCESSING` 或可重试的前置状态 | 文档 `UPLOADED`，任务 `PENDING` |
| INDEX | `INDEXED` | 任务 `SUCCEEDED`；不再重新 embedding |
| INDEX | 索引 `FAILED` | 任务 `FAILED`；不自动重试 |
| INDEX | `INDEXING` 或可重试的前置状态 | 索引 `NOT_INDEXED`，任务 `PENDING` |

After three stale recoveries by default, the task becomes `FAILED` with `STALE_RECOVERY_LIMIT_EXCEEDED` instead of looping forever. An explicit user retry starts a new recovery cycle but keeps the cumulative attempt count.

默认情况下，经过三次过期恢复之后，任务会以 `STALE_RECOVERY_LIMIT_EXCEEDED` 变为 `FAILED`，而不是无限循环。用户显式重试会开启一个新的恢复周期，但保留累计的尝试次数。

Recovery also repairs orphan business states left by older code or abnormal failure: `PROCESSING` without an active process task is restored to `UPLOADED` with a logical process task in `PENDING`; `INDEXING` without an active index task is restored to `NOT_INDEXED` with an index task in `PENDING`. A fresh active task is never taken from another worker.

恢复还会修复旧代码或异常失败遗留下来的孤儿业务状态：没有活跃 process 任务的 `PROCESSING` 会被恢复为 `UPLOADED`，并生成一个处于 `PENDING` 的逻辑 process 任务；没有活跃 index 任务的 `INDEXING` 会被恢复为 `NOT_INDEXED`，并生成一个处于 `PENDING` 的 index 任务。新近产生的活跃任务永远不会从其他 worker 手中被夺走。

## At-least-once execution and idempotency（至少一次执行与幂等性）

NexusMind does **not** claim exactly-once execution. MySQL business state, the embedding provider, and Milvus cannot participate in one local ACID transaction. For example, Milvus can accept an upsert and the JVM can crash before the task row is marked successful. Recovery may then execute the task again.

NexusMind **并不**声称精确一次执行。MySQL 业务状态、embedding 提供商和 Milvus 无法参与同一个本地 ACID 事务。例如，Milvus 可以接受一次 upsert，而 JVM 在任务行被标记成功之前崩溃。之后恢复可能会再次执行该任务。

The design is therefore:

因此设计如下：

```text
Durable DB task
+ at-least-once execution
+ idempotent business operation
+ recovery and reconciliation
```

Processing replaces the complete document chunk set in one MySQL transaction: old chunks are deleted, new chunks are inserted in bounded batches, and the document becomes `READY` only after all inserts succeed. Repeating process converges to the same ordered chunk set instead of appending duplicates.

处理（process）在一个 MySQL 事务中整体替换文档的 chunk 集合：删除旧 chunk，按有界批次插入新 chunk，只有在所有插入都成功之后文档才变为 `READY`。重复执行 process 会收敛到同一份有序的 chunk 集合，而不是追加重复数据。

Indexing reads current MySQL chunks and upserts vectors under stable `chunk_id` identities. Repeating index replaces/finishes the same derived projection, including after a crash that left only part of the document in Milvus. MySQL chunks remain authoritative.

索引（index）读取 MySQL 中当前的 chunk，并以稳定的 `chunk_id` 身份 upsert 向量。重复执行 index 会替换/补完同一份派生投影，包括在崩溃只把文档的一部分写入 Milvus 之后。MySQL 中的 chunk 始终是权威数据。

An ordinary execution exception records a concise, bounded error and leaves the task/document in their existing failed semantics. Checkpoint 16 intentionally performs no automatic provider retry; the user can explicitly retry the same logical task.

普通的执行异常会记录一条简洁、有界的错误，并让任务/文档保持原有的失败语义。Checkpoint 16 有意不做自动的提供商重试；用户可以显式重试同一个逻辑任务。

## HTTP and frontend behavior（HTTP 与前端行为）

The existing process and index URLs now enqueue work. New or active work returns `202 Accepted`; an already-complete logical operation returns `200 OK`. The response exposes task identity, type, status, attempt count, timestamps, and a safe error summary, but not worker IDs, run tokens, recovery counters, or stack traces.

现有的 process 和 index URL 现在改为入队工作。新增或处于活跃状态的工作返回 `202 Accepted`；已经完成的逻辑操作返回 `200 OK`。响应暴露任务标识、类型、状态、尝试次数、时间戳和安全的错误摘要，但不暴露 worker ID、run token、恢复计数或堆栈信息。

The frontend fetches active tasks once when a knowledge base is selected and polls only while `PENDING` or `RUNNING` tasks exist. It renders `Queued`, `Processing…`/`Indexing…`, terminal failure and retry state. Polling stops after terminal states. Reloading the page reconstructs active UI state from one knowledge-base-level task query rather than one request per document. Process and Index remain separate explicit actions; upload does not start processing, and processing success does not automatically enqueue indexing.

前端在选中知识库时拉取一次活跃任务，之后只在存在 `PENDING` 或 `RUNNING` 任务时轮询。它渲染 `Queued`、`Processing…`/`Indexing…`、终态失败和重试状态。到达终态后停止轮询。刷新页面时通过一次知识库级别的任务查询重建活跃的 UI 状态，而不是为每个文档发一个请求。Process 和 Index 仍然是两个独立的显式操作；上传不会启动处理，处理成功也不会自动入队索引。

## Configuration（配置）

```yaml
nexusmind:
  document-task:
    enabled: true
    worker-enabled: true
    worker-concurrency: 2
    poll-interval: 1s
    heartbeat-interval: 10s
    stale-after: 5m
    recovery-interval: 30s
    max-stale-recoveries: 3
    shutdown-await: 30s
```

`worker-enabled=false` is intended for deterministic integration fixtures that inspect queue state without a background consumer. Production-like local operation enables both the task API and worker.

`worker-enabled=false` 用于确定性的集成测试夹具，让它们在没有任何后台消费者的情况下检查队列状态。类生产的本地运行会同时启用任务 API 和 worker。

## Checkpoint boundary and final status（Checkpoint 边界与最终状态）

- Provider bounded retry and exponential backoff were added later in V4; circuit breaking and model fallback remain out of scope.
  - V4 后续已增加 Provider 有界重试和指数退避；熔断与模型回退仍不在范围内。
- Task history is represented by the current logical row and counters, not a full execution audit log.
  - 任务历史由当前的逻辑行和计数器表示，而不是完整的执行审计日志。
- A run token fences task-row completion but cannot revoke an external side effect already in progress.
  - run token 对任务行的完成做围栏，但无法撤销已经在进行中的外部副作用。
- Graceful shutdown is bounded; recovery is still required after forced termination.
  - 优雅关闭是有界的；强制终止后仍然需要恢复。
- Hybrid retrieval routes were parallelized later in V4 without changing ranking semantics.
  - V4 后续已并行化 Hybrid routes，ranking 语义不变。
- Agent sessions later gained a MySQL lease and fenced memory commit.
  - Agent Session 后续增加了 MySQL lease 和带 fencing 的 Memory Commit。
- Token budgets and Micrometer/Prometheus metrics are implemented; distributed tracing and an operations dashboard remain out of scope.
  - Token Budget 与 Micrometer/Prometheus 指标已经实现；分布式追踪和运维 Dashboard 仍不在范围内。
