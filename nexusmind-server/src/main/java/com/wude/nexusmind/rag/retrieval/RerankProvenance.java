package com.wude.nexusmind.rag.retrieval;

public record RerankProvenance(
        int preRerankRank,
        double preRerankScore,
        RetrievalScoreType preRerankScoreType) {

    public RerankProvenance {
        if (preRerankRank <= 0) {
            throw new IllegalArgumentException("Pre-rerank rank must be 1-based");
        }
        if (!Double.isFinite(preRerankScore)) {
            throw new IllegalArgumentException("Pre-rerank score must be finite");
        }
        if (preRerankScoreType != RetrievalScoreType.RRF) {
            throw new IllegalArgumentException("Hybrid rerank provenance must preserve an RRF score");
        }
    }
}
