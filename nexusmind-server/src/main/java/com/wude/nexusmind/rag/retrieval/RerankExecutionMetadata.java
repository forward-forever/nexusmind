package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.rag.rerank.RerankResult;

public record RerankExecutionMetadata(
        String model,
        int candidateCount,
        int requestedTopN,
        long latencyMs,
        RerankResult.Usage usage) {

    public RerankExecutionMetadata {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Rerank execution model is required");
        }
        if (candidateCount < 0 || requestedTopN <= 0 || latencyMs < 0) {
            throw new IllegalArgumentException("Invalid rerank execution metadata");
        }
    }
}
