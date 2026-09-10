# Dense Vector Retrieval

Checkpoint 4 实现 NexusMind 的第一版 Dense Retrieval：

```text
MySQL KnowledgeChunk
        ↓
Spring AI EmbeddingModel
        ↓
Milvus FloatVector + HNSW
        ↓
Query Embedding
        ↓
COSINE TopK Search
```

MySQL 是业务数据的 Source of Truth，Milvus 是可由 MySQL Chunk 重建的 Retrieval Projection。`one KnowledgeBase → one Collection` 是 NexusMind V1 的设计选择，不是 Milvus 的强制要求。

## 运行配置

local profile 通过环境变量读取 `DASHSCOPE_API_KEY`、`EMBEDDING_BASE_URL` 和 `MILVUS_URI`。密钥不进入 YAML、测试或日志。业务空间 API Key 必须配合其凭据 CSV 中的 `openAiCompatible` 专属 base URL，不能改成通用 DashScope 地址，也不要在 base URL 末尾追加 `/embeddings`；Spring AI 会自行追加资源路径。

```yaml
nexusmind:
  ai:
    embedding:
      model: qwen3.7-text-embedding-flash
      dimension: 1024
      batch-size: 15
      max-batch-chars: 7500
  milvus:
    content-max-length: 8192
    hnsw:
      m: 32
      ef-construction: 200
      ef: 64
```

Spring AI OpenAI Embedding 的 model 和 dimensions 引用上述 NexusMind 配置，避免两套配置出现不一致。文档 Embedding 批次同时受 15 条和 7500 chars 限制；7500 是 NexusMind 当前实测稳定 baseline，不是 Qwen 官方限制。单个超限 Chunk 会独立成批，避免 planner 死循环。这些 HNSW 参数同样只是 V1 baseline，不代表最优值。

Spring AI 2.0.0 原生支持 `spring.ai.openai.embedding.timeout` 和 `max-retries`，本项目默认分别为 60 秒和 1 次重试。不需要另外定制 OkHttp timeout Bean。

> 排查提示：如果直接 curl 业务空间 endpoint 成功，但 Spring AI 请求长时间后 timeout，先确认 `EMBEDDING_BASE_URL` 是同一份凭据的 `openAiCompatible` 值，而不是通用 DashScope endpoint。修改 batch-size 不能修复 API Key 与 endpoint 不匹配。

## Collection 与 Schema

KnowledgeBase `12` 映射为 `kb_12`。Collection 在第一次索引 Document 时惰性创建，dynamic field 关闭，consistency 使用 `STRONG`。

| Field | Milvus type | 说明 |
| --- | --- | --- |
| `chunk_id` | Int64 | Primary Key，`autoID=false`，直接使用 MySQL `knowledge_chunk.id` |
| `knowledge_base_id` | Int64 | KnowledgeBase ID |
| `document_id` | Int64 | Document ID |
| `chunk_index` | Int32 | Document 内的连续 Chunk 序号 |
| `content` | VarChar(8192) | 超限明确失败，不截断 |
| `page_no` | Int32 nullable | PDF 页码 |
| `section_title` | VarChar(2048) nullable | Markdown 章节标题 |
| `embedding` | FloatVector(1024) | HNSW + COSINE |

已存在的 Collection 会校验 Primary Field、Embedding Field 和 Dimension。Schema 不一致时明确失败，绝不自动 drop。

## Index 生命周期

`knowledge_document.status=READY` 仅表示 Parser、Chunk 和 MySQL 阶段成功。Milvus 状态由独立字段表达：

```text
NOT_INDEXED → INDEXING → INDEXED
FAILED      → INDEXING → FAILED
```

开始和完成/失败状态更改分别使用短 MySQL 事务。Embedding 与 Milvus 操作在事务外执行。Milvus 使用稳定 `chunk_id` upsert。中途失败时尝试按 `document_id` 删除已写入的 Entity，再将 `index_status` 记为 `FAILED`。这是状态机、幂等 upsert 和 best-effort 补偿，不是 MySQL + Milvus 分布式事务。

Milvus Search 只产生候选命中。Dense Retriever 会 over-fetch，并一次批量查询 MySQL；只有同属当前 KnowledgeBase 且 `status=READY`、`index_status=INDEXED` 的 Document 才能形成业务可见的 Retrieval Hit。FAILED、INDEXING、NOT_INDEXED、跨知识库和 orphan 候选全部过滤，过滤后仍保持原候选排名。这使补偿删除失败时残留的 derived vector 不会进入 RAG。

## 本地端到端验证

先启动 MySQL 和 Milvus，准备 local profile 所需环境变量，然后启动服务：

```bash
cd /Users/wude/IdeaProjects/nexusmind/deploy
docker compose up -d

cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
set -a
source ../deploy/.env
set +a
export DASHSCOPE_API_KEY='<your-local-key>'
export EMBEDDING_BASE_URL='https://ws-your-workspace-id.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'
export EMBEDDING_TIMEOUT='60s'
export EMBEDDING_MAX_RETRIES='1'
export MILVUS_URI='http://localhost:19530'
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

创建 KnowledgeBase（Embedding 模型和维度由服务端配置自动写入）：

```bash
curl -sS -X POST http://localhost:8080/api/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"Dense Retrieval Demo","description":"Checkpoint 4"}'
```

上传、解析切分和索引（替换 KnowledgeBase ID、Document ID 和 PDF 路径）：

```bash
curl -sS -X POST http://localhost:8080/api/knowledge-bases/10/documents \
  -F 'file=@/absolute/path/to/mysql-deadlocks.pdf'
curl -sS -X POST http://localhost:8080/api/documents/20/process
curl -sS -X POST http://localhost:8080/api/documents/20/index
```

分别使用相关问题和无关问题观察原始 COSINE score：

```bash
curl -sS -X POST http://localhost:8080/api/knowledge-bases/10/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"MySQL 为什么会发生死锁？","topK":5}'

curl -sS -X POST http://localhost:8080/api/knowledge-bases/10/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"如何种植太空中的红色玫瑰？","topK":5}'
```

COSINE score 越高表示越相似；响应保留 Milvus 原始 score 并按降序返回，不做 `1 - score`、threshold、rerank 或 LLM 回答。

当 Document 包含大量 Chunk 时，同步 Index API 会串行执行多个批次。例如 698 个 Chunk 在当前 batch-size=15 时需要 47 次 Embedding 请求；人工 curl 验证应使用足够大的 `--max-time`。这是 V1 同步方案的已知限制，后续应通过异步 Index Task 解决，而不是违反 Provider 限制地扩大批次。

## 当前边界

RAG Answer 已在 Checkpoint 5 基于本检索器实现。BM25、Sparse Vector、Hybrid Search、RRF、Rerank、Agent 和异步索引任务仍未实现。
