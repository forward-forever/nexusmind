# Durable asynchronous document tasks

Checkpoint 16 moves document processing and indexing out of the HTTP request lifecycle. The API now commits a task to MySQL and returns immediately; a bounded background worker claims and executes that durable task.

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

## Why MySQL instead of a message broker

NexusMind is currently a single deployable service and already requires MySQL for document and chunk state. A MySQL-backed task row gives the application durable acceptance, row locking, atomic claiming, uniqueness, inspection, and recovery without introducing Kafka, RabbitMQ, or a separate workflow service. The task table is execution-coordination state; `knowledge_document` and `knowledge_chunk` remain the business source of truth, while Milvus remains a derived retrieval projection.

An in-memory queue, application event, or fire-and-forget future would lose accepted work on a JVM crash. The API therefore reports success only after the task enqueue transaction commits.

## State machine and logical identity

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

Enqueue operations use `knowledge_document SELECT ... FOR UPDATE` as their serialization point and the unique task key as a second line of defense. A repeated request returns the same active task. Enqueueing does not prematurely set `PROCESSING` or `INDEXING`: those states begin only when a worker actually executes the business service.

## Claiming and ownership fencing

Workers reserve a local executor slot before claiming. The short claim transaction uses `FOR UPDATE SKIP LOCKED`, changes one `PENDING` row to `RUNNING`, increments `attempt_count`, and assigns:

- an instance-level `worker_id` for diagnostics;
- a fresh per-attempt UUID `run_token`.

Heartbeat, success, and failure writes require `id`, `RUNNING`, and the current `run_token`. If an old worker resumes after recovery and a new worker has claimed the task, the old token updates zero rows and cannot overwrite the new task owner. This is task-row fencing, not a guarantee that an old process cannot already have made an external side effect.

The dedicated executor defaults to two workers and has no backlog for claimed tasks. Shutdown stops new claims and allows running tasks a bounded completion window. Work killed after that window remains `RUNNING` and is handled by stale recovery after restart.

## Heartbeat and crash recovery

The defaults are a 10-second heartbeat, a five-minute stale threshold, and a 30-second recovery scan. `stale-after` must be greater than `heartbeat-interval`. Recovery runs once after application startup and periodically thereafter.

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

| Task | Business state | Recovery result |
|---|---|---|
| PROCESS | `READY` | task `SUCCEEDED`; do not parse again |
| PROCESS | `FAILED` | task `FAILED`; do not auto-retry |
| PROCESS | `PROCESSING` or retryable pre-state | document `UPLOADED`, task `PENDING` |
| INDEX | `INDEXED` | task `SUCCEEDED`; do not embed again |
| INDEX | index `FAILED` | task `FAILED`; do not auto-retry |
| INDEX | `INDEXING` or retryable pre-state | index `NOT_INDEXED`, task `PENDING` |

After three stale recoveries by default, the task becomes `FAILED` with `STALE_RECOVERY_LIMIT_EXCEEDED` instead of looping forever. An explicit user retry starts a new recovery cycle but keeps the cumulative attempt count.

Recovery also repairs orphan business states left by older code or abnormal failure: `PROCESSING` without an active process task is restored to `UPLOADED` with a logical process task in `PENDING`; `INDEXING` without an active index task is restored to `NOT_INDEXED` with an index task in `PENDING`. A fresh active task is never taken from another worker.

## At-least-once execution and idempotency

NexusMind does **not** claim exactly-once execution. MySQL business state, the embedding provider, and Milvus cannot participate in one local ACID transaction. For example, Milvus can accept an upsert and the JVM can crash before the task row is marked successful. Recovery may then execute the task again.

The design is therefore:

```text
Durable DB task
+ at-least-once execution
+ idempotent business operation
+ recovery and reconciliation
```

Processing replaces the complete document chunk set in one MySQL transaction: old chunks are deleted, new chunks are inserted in bounded batches, and the document becomes `READY` only after all inserts succeed. Repeating process converges to the same ordered chunk set instead of appending duplicates.

Indexing reads current MySQL chunks and upserts vectors under stable `chunk_id` identities. Repeating index replaces/finishes the same derived projection, including after a crash that left only part of the document in Milvus. MySQL chunks remain authoritative.

An ordinary execution exception records a concise, bounded error and leaves the task/document in their existing failed semantics. Checkpoint 16 intentionally performs no automatic provider retry; the user can explicitly retry the same logical task.

## HTTP and frontend behavior

The existing process and index URLs now enqueue work. New or active work returns `202 Accepted`; an already-complete logical operation returns `200 OK`. The response exposes task identity, type, status, attempt count, timestamps, and a safe error summary, but not worker IDs, run tokens, recovery counters, or stack traces.

The frontend fetches active tasks once when a knowledge base is selected and polls only while `PENDING` or `RUNNING` tasks exist. It renders `Queued`, `Processing…`/`Indexing…`, terminal failure and retry state. Polling stops after terminal states. Reloading the page reconstructs active UI state from one knowledge-base-level task query rather than one request per document. Process and Index remain separate explicit actions; upload does not start processing, and processing success does not automatically enqueue indexing.

## Configuration

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

## Known limitations

- Provider retry, exponential backoff, circuit breaking, and model fallback are not implemented.
- Task history is represented by the current logical row and counters, not a full execution audit log.
- A run token fences task-row completion but cannot revoke an external side effect already in progress.
- Graceful shutdown is bounded; recovery is still required after forced termination.
- Hybrid retrieval routes remain serial.
- Agent sessions do not yet have backend concurrency control.
- Token budgets, production metrics, distributed tracing, and an operations dashboard are not implemented.

