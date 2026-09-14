# V2 Retrieval Quality — Final Comparison

## Scope and evidence

NexusMind V2 建立了四条可独立选择、可使用同一 Golden Dataset 重复评估的 Retrieval Pipeline：`DENSE`、`BM25`、`HYBRID_RRF`、`HYBRID_RERANK`。

以下数字读取自 `evaluation/reports` 中 2026-09-14 对 `benchmark-local-golden` 的四份真实 JSON Report。Dataset 包含 30 条 Query，对应一个 22 页、每页一个独立知识单元的 synthetic technical corpus；Chunk baseline 为 500 chars、100 chars overlap。

| Strategy | Hit@1 | Hit@5 | Recall@5 | MRR@5 | Avg | P95 |
|---|---:|---:|---:|---:|---:|---:|
| DENSE | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 184.90 ms | 199 ms |
| BM25 | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 20.83 ms | 21 ms |
| HYBRID_RRF | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 201.27 ms | 212 ms |
| HYBRID_RERANK | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 445.30 ms | 715 ms |

四条路线的 Recall@1 都是 `0.9667`：其中一个 Query 标注了多个 relevant chunks，rank 1 已经命中答案，但不可能在单个位置召回全部 relevant chunks。

这些 latency 是本地开发环境端到端 Retrieval latency，不是 production benchmark。HYBRID_RERANK 的最大值为 1243 ms，也说明外部 Provider latency 会带来更明显的长尾。

## Four retrieval strategies

### DENSE

```text
Query → qwen3.7-text-embedding-flash → HNSW / COSINE
```

Dense 通过向量语义相似度处理同义改写和没有共享字面的表达；每次 Query 需要一次外部 Embedding API 调用。

### BM25

```text
Raw Query → Chinese analyzer → BM25 sparse retrieval
```

BM25 擅长精确词汇、配置名、错误码和技术缩写。本次测试中它最快，并且 Query Retrieval 不调用外部 Embedding API。

### HYBRID_RRF

```text
Dense visible ranking + BM25 visible ranking → Reciprocal Rank Fusion
```

RRF 只融合 rank，不直接比较或相加 COSINE 与 BM25 raw score。NexusMind 在每一路完成 MySQL visibility validation 后执行 Application RRF，避免 stale derived-index candidate 先参与融合。

### HYBRID_RERANK

```text
Hybrid RRF Top20 → qwen3.7-text-rerank → Final TopK
```

Cross-Encoder 对 Query 与少量 Candidate 做联合判断，目标是提高最终候选 Precision。它保留 RRF rank/score 和 Dense/BM25 contribution，但增加一次外部模型依赖、费用与最高 latency。

## Benchmark ceiling effect

当前 30 条 Query 的 HitRate@1 和 MRR@1 在四种 Strategy 上都达到 `1.0`。这证明：

- 四条 Pipeline 能正确工作；
- Evaluation Framework 可以统一比较不同 Retriever；
- V2 Benchmark 可以作为当前 Corpus 的 regression safety net；
- RRF 与 Rerank 的 ranking/provenance 链路可以被检查和解释。

它不能证明 Hybrid 显著优于 Dense，也不能证明 Rerank 显著提升 Retrieval Accuracy。Corpus 小、知识单元边界清晰、问题较明确，当前 Dataset 已无法分辨进一步增益。它同样不能证明高级路线在大型、噪声更多、概念重叠的真实 Corpus 上没有价值。

V2 不修改 Golden Label 来制造提升，也不继续针对这个小 Dataset 调参。

## Quality, latency, and cost

| Strategy | Main capability | External query model | Engineering trade-off |
|---|---|---|---|
| BM25 | Lexical exact match | None | 最快，但语义改写能力有限 |
| DENSE | Semantic matching | Embedding | 语义能力与一次 Embedding 调用 |
| HYBRID_RRF | Rank-level route fusion | Embedding | 增加两路计算与融合复杂度 |
| HYBRID_RERANK | Candidate precision | Embedding + Rerank | Pipeline 最完整，同时 latency、外部依赖和费用最高 |

复杂度不是质量的替代指标。任何默认策略调整都应该由更具区分度的真实 Corpus Evaluation 支持。

## Why the product default remains DENSE

当前产品配置继续保持：

```yaml
nexusmind:
  rag:
    retriever: DENSE
```

Dense 已在当前 Benchmark 达到 perfect HitRate@1/MRR@1。现有实验没有证明 Hybrid 或 Rerank 的额外 latency、Provider dependency 与 cost 带来可观测的质量收益，因此默认启用更复杂路径没有依据。

这不是永久决定。未来若真实 Corpus Evaluation 证明高级路线显著提升，可以通过现有 Registry/configuration 切换，而不需要改造 RAG 上层。

## V2 engineering lessons

1. MySQL 是业务 Source of Truth，Milvus 是可重建的 Derived Index。
2. RAG 上层依赖 `RetrievalService`，不绑定 Dense/Milvus 实现。
3. COSINE 与 BM25 raw score 不在同一尺度，不能直接相加。
4. Application RRF 在各路 MySQL visibility validation 之后执行。
5. Recall Candidate TopN 与面向调用方的 Final TopK 分离。
6. Rerank 后保留 upstream RRF 与 Dense/BM25 provenance。
7. Evaluation 必须先于算法优化，Golden Label 不能跟随 Retriever 输出修改。
8. Retrieval 策略必须同时权衡 Quality、Latency、Cost 和 failure dependency。

## V2 freeze

V2 不再增加 Retrieval Algorithm。Evaluation Dataset、Page-to-Chunk Resolver、Report Writer、四个 Retriever 和 Retrieval Lab 继续保留，作为后续回归与面试演示资产。并行检索、retry、fallback、circuit breaker、caching 与 production observability 留给 V4。
