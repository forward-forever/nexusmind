# Dense + BM25 Application RRF

Checkpoint 9 在不改变 Dense、BM25 参数和 V1 产品默认 Retriever 的前提下，增加可独立评测的 `HYBRID_RRF`。

## 为什么需要 Hybrid

Dense Retrieval 擅长语义相似，例如用户没有复用原文关键词的改写问题；BM25 擅长词法精确匹配，例如错误码、SQL 关键字、配置名、类名和缩写。真实 Golden Dataset 已经观察到 Dense miss/BM25 hit 与 BM25 miss/Dense hit，因此两条路线具备可验证的互补性。

COSINE 与 BM25 raw score 没有统一尺度：前者是向量相似度，后者是词法相关性分数。直接相加、归一化或套用权重会引入未经评测的假设。本阶段使用只依赖排名的 Reciprocal Rank Fusion：

```text
RRF(d) = Σ 1 / (k + rank_i(d))
```

`rank_i` 使用 1-based rank；Chunk 不在某一路时该路贡献为 0。当前 `k=60` 是 baseline，不是针对 Golden Dataset 调优后的结果。Hybrid 是 union fusion：只有一路命中的 Chunk 仍是合法候选。

## 为什么使用应用层 RRF

Milvus 原生支持 Hybrid Search 和 RRF。NexusMind 当前没有采用原生融合，不代表应用层方案普遍优于 Milvus。

当前一致性边界是：

```text
Dense Milvus candidates ─→ MySQL visibility validation ─→ Dense visible ranking
BM25 Milvus candidates  ─→ MySQL visibility validation ─→ BM25 visible ranking
                                                        ↓
                                                Application RRF
```

只有 `READY + INDEXED + current KnowledgeBase` 的 Document 对业务可见。先过滤再融合可以避免 stale/orphan derived index candidate 影响 route rank、RRF score 和最终候选竞争，也保留两路 contribution 供 Debug 与 Evaluation 解释。

## Candidate Depth

Hybrid 最终 TopK 与每路候选深度分开：

```text
routeCandidateK = min(
    max(topK * 4, 20, topK),
    60
)
```

因此 final Top5 请求每路 Top20，final Top10 请求每路 Top40。Dense/BM25 内部仍使用原有 Milvus over-fetch 和 MySQL visibility filter，Hybrid 不绕过路线实现。上述参数也是固定 baseline，本轮不调优。

## Contribution 与排序

最终 `RetrievalHit` 使用：

```text
retrieverType = HYBRID_RRF
scoreType = RRF
score = final RRF score
```

每个 Hit 保存 route contribution：`retrieverType`、1-based `rank`、`rawScore`、`rawScoreType`。Raw score 只用于 Debug 和报告，绝不参与 RRF 计算。普通 Dense/BM25 Hit 的 contributions 为空。

RRF score 相同时使用确定性 tie-break：

1. contribution count DESC
2. best route rank ASC
3. chunkId ASC

相同输入始终得到相同排序。相同 chunkId 在最终结果只出现一次；第一条有效 Hit 的 metadata 作为 canonical metadata，路线间 documentId/content 冲突会记录 WARN。

## 失败与成本

两路按 `Dense → BM25 → RRF` 顺序执行。一路返回空时仍使用另一路；两路都空时返回空结果。一路抛出 Embedding、Milvus 或 Schema 异常时 Hybrid 整体失败，不做静默降级，确保 `HYBRID_RRF` Evaluation 可解释。

每个 Hybrid Query 调用 Dense Retriever 一次，因此通常只有一次 Query Embedding；BM25 route 不调用外部 Embedding。串行 Hybrid latency 会高于单路，Evaluation 继续记录 average、p50、p95 和 max。本阶段不引入并行线程或生产 fallback。

## Debug 与 Evaluation

同一 Debug Search API 通过 `retrieverType` 选择 `DENSE`、`BM25` 或 `HYBRID_RRF`。Hybrid response 的每个 Hit 包含 RRF score 和 route contributions。

正式评测必须复用已有 `local-golden.jsonl`：

```bash
cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server

set -a
source ../deploy/.env
set +a

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.ai.model.chat=none --nexusmind.rag.enabled=false --nexusmind.evaluation.enabled=true --retriever=HYBRID_RRF --dataset=/Users/wude/IdeaProjects/nexusmind/evaluation/datasets/local-golden.jsonl --output=/Users/wude/IdeaProjects/nexusmind/evaluation/reports"
```

报告会记录 RRF k、routes、candidate depth、contributions、ranking metrics 和完整 retrieval latency。V1 RAG 默认仍为 `DENSE`；只有正式 Evaluation 完成后才决定是否切换产品默认值。Cross-Encoder Rerank 仍为 Planned。
