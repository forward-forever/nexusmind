package com.wude.nexusmind.rag.retrieval;

public record RetrievalContribution(
        RetrieverType retrieverType,
        int rank,
        double rawScore,
        RetrievalScoreType rawScoreType) {

    public RetrievalContribution {
        if (retrieverType == null
                || retrieverType == RetrieverType.HYBRID_RRF
                || retrieverType == RetrieverType.HYBRID_RERANK) {
            throw new IllegalArgumentException("Contribution must identify a retrieval route");
        }
        if (rank <= 0) {
            throw new IllegalArgumentException("Contribution rank must be 1-based");
        }
        if (!Double.isFinite(rawScore)) {
            throw new IllegalArgumentException("Contribution raw score must be finite");
        }
        if (rawScoreType == null
                || rawScoreType == RetrievalScoreType.RRF
                || rawScoreType == RetrievalScoreType.RERANK) {
            throw new IllegalArgumentException("Contribution must preserve its route score type");
        }
    }
}
