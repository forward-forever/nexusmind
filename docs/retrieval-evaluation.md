# Retrieval Evaluation Baseline

Checkpoint 7 只评估以下链路：

```text
Question
  ↓
RetrievalService.retrieve(..., topK=10)
  ↓
Retrieved Chunk Ranking
```

它不调用 ChatModel，不评估 Context、答案或 Citation，也不实现 Rerank、Query Rewrite 或 Multi Query。Checkpoint 9 起，同一评测引擎可通过 Registry 选择 `DENSE`、`BM25` 或 `HYBRID_RRF`，三者必须使用同一份 Golden Dataset。

## Golden Dataset

Dataset 是 UTF-8 JSONL，每行格式如下：

```json
{"id":"q001","knowledgeBaseId":12,"question":"InnoDB 发生死锁的必要条件是什么？","relevantChunkIds":[101,102],"category":"EXACT","note":"人工确认"}
```

字段：

- `id`：Dataset 内唯一、非空。
- `knowledgeBaseId`：当前 MySQL 中存在的 KnowledgeBase。
- `question`：非空的真实问题。
- `relevantChunkIds`：至少一个、不重复，使用 `knowledge_chunk.id`。
- `category`：`EXACT`、`SEMANTIC`、`SHORT`、`LONG`、`ABBREVIATION`、`OTHER` 之一。
- `note`：可选的人工标注说明。

`knowledge_chunk.id` 同时也是 Milvus `chunk_id` 和 `RetrievalHit.chunkId`，因此 V2 第一版使用它作为明确的 Ranking Label。修改 Chunk Strategy、Chunk Size 或重新 Process 文档可能改变 Chunk ID/Boundary；发生这些变化后，Dataset 必须重新标注或迁移，不能假设 Label 永久稳定。

本轮只收录知识库中确实有答案的问题。Out-of-scope、拒答与生成质量留给后续独立评测。

## 人工标注流程

第一轮建议准备 30～50 条问题，最低可以从 20 条开始建立机制，但 20 条不具有统计代表性。问题应覆盖 EXACT、SEMANTIC、SHORT、LONG、ABBREVIATION，避免全部照抄原文。

1. 先写真实用户可能提出的问题，不要先看 Dense TopK。
2. 使用 `GET /api/documents/{documentId}/chunks` 或 MySQL 查看候选 Chunk。
3. 人工判断哪些 Chunk 真正能够支持回答。
4. 把对应 `knowledge_chunk.id` 写入 `relevantChunkIds`；一个问题可以有多个 relevant Chunk。
5. 将本地文件命名为 `evaluation/datasets/local-<name>.jsonl`，它默认不会提交 Git。

禁止采用“Dense 返回什么就把什么标成 Golden”的方式，否则 Dataset 会复制当前 Retriever 的偏差，失去比较意义。

运行前会严格验证：ID 唯一、问题非空、标签非空且不重复、KnowledgeBase 存在、Chunk 存在且属于指定 KnowledgeBase，以及 Chunk 所属 Document 为 `READY + INDEXED`。所有 expected Chunk 和 Document 分别批量查询；坏标签会让整次评测失败，不会被静默跳过。

## Page-based Benchmark Labeling

对于专门设计的 `nexusmind-retrieval-benchmark-v1.pdf`，可以先人工在 source JSONL 中标注页码，再用离线 Resolver 将页码转换为 ingestion 后真实生成的 `knowledge_chunk.id`。该 Benchmark PDF 遵循“一页一个知识单元”、页内正文小于当前 Chunk baseline、PDF Chunk 不跨页的约束，因此页码可以作为这套特定 Corpus 的低成本中间标签。

Source JSONL 每行格式：

```json
{"id":"q001","question":"InnoDB 为什么会产生死锁？","category":"SEMANTIC","expectedPages":[4],"expectedConcept":"InnoDB deadlock","note":"人工页码标注"}
```

Resolver 从 `documentId` 查询真实 `knowledgeBaseId`，一次加载该 Document 的全部 Chunk，按 `expectedPages` 顺序及页内 `chunkIndex` 顺序映射 ID，并输出原 Evaluation Engine 可直接读取的 Golden JSONL：

```text
Source Dataset (expectedPages)
  ↓ BenchmarkDatasetResolver
Golden Dataset (relevantChunkIds)
  ↓ Existing RetrievalEvaluationDatasetLoader
DENSE / BM25 / HYBRID_RRF Evaluation
```

运行前确保目标 Document 已经完成 `READY + INDEXED`。Resolver 不需要 Milvus、Embedding 或 Chat Model：

```bash
cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
set -a
source ../deploy/.env
set +a

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.ai.model.chat=none --spring.ai.model.embedding=none --nexusmind.vector.enabled=false --nexusmind.rag.enabled=false --nexusmind.dataset-resolver.enabled=true --document-id=<DOCUMENT_ID> --source=/absolute/path/nexusmind-retrieval-benchmark-questions.source.jsonl --output=/absolute/path/local-golden.jsonl"
```

任何 source 格式错误、重复 ID、非法页码、缺页或跨 Document/KnowledgeBase 的 Chunk 数据都会使整个转换失败，不会跳过问题。一页映射到多个 Chunk 时，全部 Chunk ID 都进入 `relevantChunkIds`；超过两个 Chunk 会输出 sanity warning，提示人工核对 Benchmark 的 Chunk boundary。

这一方法仅适用于上述人为控制页边界的 Benchmark Corpus。普通任意 PDF 的“一页”可能包含多个主题，相关答案也可能跨页或只由页内部分 Chunk 支持，仍然必须进行人工 relevance judgment；Page Resolver 不是通用自动标注算法。

## Metrics

每条 Query 只调用一次 `RetrievalService.retrieve(..., 10)`，然后在内存中计算 `K=1/3/5/10`：

- `HitRate@K`：TopK 至少包含一个 relevant Chunk 的 Query 占比。
- `Recall@K`：每个 Query 的 `命中 relevant 数 / relevant 总数`，再对 Query 做 Macro Average。
- `MRR@K`：TopK 内第一个 relevant Chunk 的 1-based rank 的倒数；没有命中为 0，再对 Query 求平均。

本轮没有 graded relevance，因此不计算 NDCG。

Latency 覆盖整个 `RetrievalService.retrieve()`：Dense 包括 Query Embedding、Milvus Search 和 MySQL visibility validation；BM25 包括 Milvus raw-text BM25 Search 和相同的 MySQL visibility validation；Hybrid 当前顺序执行这两条已过滤路线，再执行应用层 RRF。输出 average、nearest-rank p50、p95 和 max。它只是当前本地开发环境 baseline，不是 production benchmark。

报告还按实际存在的 Query Category 输出 `queryCount`、`HitRate@5` 和 `MRR@5`，并单列 Top5 miss 或第一个 relevant 只出现在 rank 6～10 的 failure cases。

## CLI

真实 Dense Evaluation 需要已启动的 MySQL、Milvus，以及本地环境中的 `DASHSCOPE_API_KEY`、`EMBEDDING_BASE_URL`。它只对每条 Query 调用一次 Embedding，不调用 qwen3.5-flash、不重建向量、不重新 Index。BM25 Evaluation 不调用外部 Embedding API，可以显式关闭 Spring AI Embedding Model。

```bash
cd /Users/wude/IdeaProjects/nexusmind/deploy
docker compose up -d

cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
set -a
source ../deploy/.env
set +a

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.ai.model.chat=none --nexusmind.rag.enabled=false --nexusmind.evaluation.enabled=true --retriever=DENSE --dataset=/absolute/path/to/local-golden.jsonl --output=/Users/wude/IdeaProjects/nexusmind/evaluation/reports"
```

BM25 使用同一 Dataset：

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.ai.model.chat=none --spring.ai.model.embedding=none --nexusmind.rag.enabled=false --nexusmind.evaluation.enabled=true --retriever=BM25 --dataset=/absolute/path/to/local-golden.jsonl --output=/Users/wude/IdeaProjects/nexusmind/evaluation/reports"
```

Hybrid 同样使用该 Dataset。它会运行一次 Dense route（即一次 Query Embedding）和一次不调用外部 Embedding 的 BM25 route：

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.ai.model.chat=none --nexusmind.rag.enabled=false --nexusmind.evaluation.enabled=true --retriever=HYBRID_RRF --dataset=/absolute/path/to/local-golden.jsonl --output=/Users/wude/IdeaProjects/nexusmind/evaluation/reports"
```

`RetrievalEvaluationCli` 复用真实 Spring Context 和 `RetrievalEvaluationService`，完成后关闭 Context 并退出，不启动 Web Server，也不暴露 Evaluation REST API。

## Reports

每次运行在 output 目录生成同名 JSON 与 Markdown，例如：

```text
dense-20260910-173000-000.json
dense-20260910-173000-000.md
bm25-20260910-173100-000.json
bm25-20260910-173100-000.md
hybrid_rrf-20260910-173200-000.json
hybrid_rrf-20260910-173200-000.md
```

JSON 保存 baseline metadata、全部 case ranking、指标、latency、category breakdown 和 failure cases。Hybrid metadata 还记录 RRF k、两条 route 及 route candidate depth 参数；每个 Hybrid hit 保存 Dense/BM25 rank 与原始 score contribution。Markdown 提供指标表、latency 表、分类汇总和逐条失败分析。两种报告都只记录问题、Chunk ID、rank、score/scoreType、contribution 与 latency，不写入 Chunk content 或文档正文。

`evaluation/reports/` 默认被 Git 忽略。用户确认有价值的正式 baseline summary 后，可人工整理到 `docs/evaluation/`。
