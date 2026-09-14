package com.wude.nexusmind.rag.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusmind.rag.hybrid")
public record HybridRetrievalProperties(
        Rrf rrf,
        int routeCandidateMultiplier,
        int minRouteCandidates,
        int maxRouteCandidates) {

    public HybridRetrievalProperties {
        if (rrf == null) {
            throw new IllegalArgumentException("Hybrid RRF configuration is required");
        }
        if (routeCandidateMultiplier <= 0) {
            throw new IllegalArgumentException("Route candidate multiplier must be positive");
        }
        if (minRouteCandidates <= 0) {
            throw new IllegalArgumentException("Minimum route candidates must be positive");
        }
        if (maxRouteCandidates < minRouteCandidates) {
            throw new IllegalArgumentException(
                    "Maximum route candidates must be at least the configured minimum");
        }
        if (maxRouteCandidates < RetrievalLimits.MAX_PUBLIC_TOP_K) {
            throw new IllegalArgumentException(
                    "Maximum route candidates must cover public topK up to "
                            + RetrievalLimits.MAX_PUBLIC_TOP_K);
        }
        if (maxRouteCandidates > RetrievalLimits.MAX_ROUTE_CANDIDATES) {
            throw new IllegalArgumentException(
                    "Maximum route candidates cannot exceed " + RetrievalLimits.MAX_ROUTE_CANDIDATES);
        }
    }

    public record Rrf(int k) {
        public Rrf {
            if (k <= 0) {
                throw new IllegalArgumentException("RRF k must be positive");
            }
        }
    }
}
