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
