# Cross-Encoder Rerank

## Retriever 与 Reranker

Retriever 面向整个知识库进行快速候选召回，优先保证 Recall。NexusMind V2 使用 Dense 与 BM25 两路检索，经过应用层 RRF 得到一个规模很小的有序候选集。

Reranker 只处理这批候选，联合判断 Question 与每个 Chunk 是否构成真正的问答相关关系，优先改善最终 TopK 的 Precision。它比第一阶段检索慢，因此不直接扫描整个知识库。

```text
Question
  ├─ Dense Retrieval
  └─ BM25 Retrieval
          ↓
    Application RRF Top20
          ↓
 qwen3.7-text-rerank
          ↓
      Final TopK
```

`candidateTopN=20` 是当前 baseline；最终 `topK` 仍由调用方指定。如果最终 topK 大于 20，候选深度会扩展到至少 final topK，但不能超过当前配置上限 50。

## Bi-Encoder 与 Cross-Encoder

Dense Retriever 是 Bi-Encoder 路径：Query 和 Document 分别编码为向量，再计算向量相似度。文档向量可以提前写入 Milvus，因此适合大规模召回。

Cross-Encoder Reranker 将 `Query + Candidate Document` 联合输入专用模型，直接输出本次请求内的 relevance score。它能观察两者之间更细粒度的交互，但每次查询都要重新计算所有候选，因此只用于第二阶段的小候选集。

NexusMind 使用阿里云百炼 `qwen3.7-text-rerank`。根据[官方 Text Rerank API](https://help.aliyun.com/zh/model-studio/text-rerank-api)，该模型使用 DashScope Text Rerank 协议：

```http
POST {RERANK_BASE_URL}/services/rerank/text-rerank/text-rerank
Authorization: Bearer <server-side API key>
Content-Type: application/json
```

其中 `RERANK_BASE_URL` 是外部提供的 Workspace `/api/v1` 根地址，不能提交真实 Workspace URL 或 API Key。

```json
{
  "model": "qwen3.7-text-rerank",
  "input": {
    "query": "InnoDB 为什么会产生死锁？",
    "documents": [
      "候选 Chunk A",
      "候选 Chunk B"
    ]
  },
  "parameters": {
    "top_n": 5,
    "instruct": "Given a web search query, retrieve relevant passages that answer the query."
  }
}
```

Provider 通过返回的 input `index` 映射原始 `RetrievalHit`，不依赖返回文本匹配。最终 score type 为 `RERANK`；该 relevance score 只表达同一次请求中 Query 与候选的相对相关性，不作为概率、答案置信度或跨请求绝对分，也不设置全局 threshold。

## Provenance 与失败语义

最终 Hit 保留：

- Dense route rank、COSINE raw score；
- BM25 route rank、BM25 raw score；
- pre-rerank Hybrid RRF rank 与 RRF score；
- final RERANK score。

Rerank 最终顺序只由 Provider relevance score 决定，不与 RRF/COSINE/BM25 score 加权。分数相同时按 pre-rerank rank、chunk ID 做确定性排序。

Provider timeout、429、5xx、连接失败或协议非法都会让 `HYBRID_RERANK` 明确失败。V2 不 retry，也不静默 fallback 到 `HYBRID_RRF`，避免 Evaluation 报告与实际执行算法不一致。

## 配置

```text
nexusmind.ai.rerank.enabled=false
nexusmind.ai.rerank.model=qwen3.7-text-rerank
nexusmind.ai.rerank.base-url=${RERANK_BASE_URL:}
nexusmind.ai.rerank.api-key=${DASHSCOPE_API_KEY:}
nexusmind.ai.rerank.timeout=3s

nexusmind.rag.rerank.candidate-top-n=20
nexusmind.rag.rerank.max-candidate-top-n=50
```

默认 RAG Retriever 仍是 `DENSE`。只有显式启用 Provider 并选择 `HYBRID_RERANK` 时才会产生 Rerank API 调用。

## Benchmark Saturation

当前 30-query Benchmark 的 Dense、BM25 与 Hybrid RRF 都已达到 `HitRate@1=1.0`、`MRR@1=1.0`。Corpus 较小、每页是独立知识点且 Query 较明确，因此没有可供 Rerank 提升的指标空间。

这套 Benchmark 的当前价值是验证 Evaluation Pipeline、Two-stage Retrieval 和回归安全，不能证明生产环境中的绝对 Retrieval Quality。Checkpoint 10 应关注是否保持 relevant Chunk、候选顺序是否真实变化、端到端 latency、Provider 稳定性和 provenance，而不修改 Golden Label 来追求更好看的分数。
