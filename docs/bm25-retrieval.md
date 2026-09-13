# BM25 Sparse Retrieval

Checkpoint 8 为 NexusMind 增加独立的 Milvus BM25 Retriever。它不替换当前 Dense RAG，也不融合 Dense 与 BM25 分数。

## 原理与链路

BM25 是词项相关性排序算法，主要考虑：

- TF：查询词在文档中的出现频率，但通过 `k1` 控制频率收益的饱和速度。
- IDF：越少文档包含的词，通常区分能力越强。
- Document Length Normalization：通过 `b` 减少长文本仅因词数更多而获得的不公平优势。

NexusMind 的实现完全运行在 Milvus 2.6.22 内部：

```text
content
  ↓
Chinese Analyzer
  ↓
content_bm25 Function
  ↓
SparseFloatVector (sparse_embedding)
  ↓
SPARSE_INVERTED_INDEX + BM25
```

写入时，Java 只提供原始 `content` 和 Dense `embedding`；`sparse_embedding` 由 Milvus 自动生成。查询时，Java SDK 使用 `EmbeddedText(query)` 搜索 `sparse_embedding`，不会调用 DashScope 或其他外部 Embedding API。

## Collection Schema

一个 KnowledgeBase 仍对应 `kb_{knowledgeBaseId}`：

```text
chunk_id            Int64 primary key, autoID=false
knowledge_base_id   Int64
document_id         Int64
chunk_index         Int32
content             VarChar, analyzer enabled, match disabled
page_no             Int32 nullable
section_title       VarChar nullable
embedding           FloatVector(KB embedding dimension)
sparse_embedding    SparseFloatVector
```

Dense 索引保持 V1 参数不变：HNSW/COSINE，M=32，efConstruction=200，search ef=64。

BM25 baseline：

```text
analyzer             chinese
index                SPARSE_INVERTED_INDEX
metric               BM25
inverted_index_algo  DAAT_MAXSCORE
k1                   1.2
b                    0.75
```

这些值只是 V2 baseline，不是调优结论。BM25 raw score 越大表示 lexical relevance 越高，但它不是概率、置信度、Cosine Similarity，也不限定在 0～1；系统不归一化、不与 Dense score 相加。

## Analyzer 选择

`standard` analyzer 依赖空格和标点等边界，无法可靠处理没有空格分隔的中文，因此当前固定使用内置 `chinese` analyzer。它适合中文 baseline，但技术文档还包含英文、缩写、类名、代码符号和产品名，例如 `C++`、`Spring Boot`、`MVCC`、`RR`、`InnoDB`。如果 Evaluation 证明这些内容存在明显分词问题，再考虑 custom 或 multi-language analyzer；Checkpoint 8 不调参。

`enableMatch` 保持关闭，因为当前没有 `TEXT_MATCH()` 过滤需求。

## 旧 Collection 与显式 Rebuild

V1 Collection 的 `content` 没有 analyzer，也没有 sparse field、BM25 Function 和 sparse index。新版代码检测到这些差异时会抛出 `Collection schema upgrade required`，不会在启动、搜索或索引过程中自动 drop。

Rebuild 是开发期 destructive maintenance operation：

```text
MySQL READY Documents
  ↓
短事务重置 index_status=NOT_INDEXED
  ↓
Drop kb_{id}
  ↓
创建 Dense + BM25 新 Schema
  ↓
逐 Document 从 MySQL Chunk 重新生成 Dense Vector 并 Upsert
  ↓
Milvus 自动生成 BM25 Sparse Vector
```

执行前确保 MySQL、Milvus 正常，并已在当前终端配置 `DASHSCOPE_API_KEY` 与 `EMBEDDING_BASE_URL`。本地数据库账号可以复用 `deploy/.env`：

```bash
cd /Users/wude/IdeaProjects/nexusmind/deploy
docker compose up -d

cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
set -a
source ../deploy/.env
set +a

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.ai.model.chat=none --nexusmind.rag.enabled=false --nexusmind.rebuild.enabled=true --knowledge-base-id=<KB_ID> --confirm=DROP_AND_REBUILD"
```

确认参数是防止误操作的必要条件。Rebuild 期间该 KB 暂时不可检索；当前不提供 alias、Blue/Green 或零停机切换。

单 Document 失败时，现有索引状态机会将其标记为 `FAILED`，其他成功 Document 保持 `INDEXED`，CLI 输出失败明细并以失败结束。Collection 创建或 Drop 失败时不会假装成功；MySQL 已重置的状态仍能真实反映当前索引不可用。

## 手工比较

同一 Query 可以通过现有 Debug API 分别执行：

```bash
curl -sS -X POST http://localhost:8080/api/knowledge-bases/<KB_ID>/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"innodb_lock_wait_timeout","topK":5,"retrieverType":"DENSE"}'

curl -sS -X POST http://localhost:8080/api/knowledge-bases/<KB_ID>/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"innodb_lock_wait_timeout","topK":5,"retrieverType":"BM25"}'
```

省略 `retrieverType` 时仍默认 `DENSE`。RAG Product 默认也由 `nexusmind.rag.retriever=DENSE` 固定为 Dense。

Rebuild 后应使用同一 Golden Dataset 重新运行 Dense Evaluation，再运行 BM25 Evaluation。只有保存这两份基于同一 Collection、同一 Dataset 的报告后，才能合理分析互补 Failure Cases，并决定下一阶段是否进入 Hybrid/RRF。
