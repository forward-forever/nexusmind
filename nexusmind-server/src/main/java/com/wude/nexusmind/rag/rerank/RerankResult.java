package com.wude.nexusmind.rag.rerank;

import java.util.List;

public record RerankResult(
        List<Item> items,
        Usage usage,
        long latencyMs) {

    public RerankResult {
        items = items == null ? null : List.copyOf(items);
        if (latencyMs < 0) {
            throw new IllegalArgumentException("Rerank latency must not be negative");
        }
    }

    /**
     * A single item in the rerank result.
     *
     * @param index The index of the item in the original list.  表示该结果对应于输入 documents 列表中的原始索引位置。
     * @param relevanceScore The relevance score of the item. 该文档与查询的语义相关性得分，取值范围为 0.0 到 1.0。分数越高，相关性越强。
     */
    public record Item(int index, double relevanceScore) {
    }

    public record Usage(Long promptTokens, Long totalTokens) {
        public Usage {
            if (promptTokens != null && promptTokens < 0) {
                throw new IllegalArgumentException("Rerank prompt tokens must not be negative");
            }
            if (totalTokens != null && totalTokens < 0) {
                throw new IllegalArgumentException("Rerank total tokens must not be negative");
            }
        }
    }
}
