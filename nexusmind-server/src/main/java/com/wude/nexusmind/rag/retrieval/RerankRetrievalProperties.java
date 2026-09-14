package com.wude.nexusmind.rag.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusmind.rag.rerank")
public record RerankRetrievalProperties(int candidateTopN, int maxCandidateTopN) {

    public RerankRetrievalProperties {
        if (candidateTopN <= 0) {
            throw new IllegalArgumentException("Rerank candidate-top-n must be positive");
        }
        if (maxCandidateTopN < candidateTopN) {
            throw new IllegalArgumentException(
                    "Rerank max-candidate-top-n must be at least candidate-top-n");
        }
        if (maxCandidateTopN > RetrievalLimits.MAX_ROUTE_CANDIDATES) {
            throw new IllegalArgumentException(
                    "Rerank max-candidate-top-n cannot exceed "
                            + RetrievalLimits.MAX_ROUTE_CANDIDATES);
        }
    }
}
