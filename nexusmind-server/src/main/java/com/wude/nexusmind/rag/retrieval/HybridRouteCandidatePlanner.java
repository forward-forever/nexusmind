package com.wude.nexusmind.rag.retrieval;

public class HybridRouteCandidatePlanner {

    private final HybridRetrievalProperties properties;

    public HybridRouteCandidatePlanner(HybridRetrievalProperties properties) {
        this.properties = properties;
    }

    public int plan(int finalTopK) {
        if (finalTopK <= 0 || finalTopK > RetrievalLimits.MAX_PUBLIC_TOP_K) {
            throw new IllegalArgumentException(
                    "Hybrid topK must be between 1 and " + RetrievalLimits.MAX_PUBLIC_TOP_K);
        }
        long multiplied = (long) finalTopK * properties.routeCandidateMultiplier();
        long minimumApplied = Math.max(Math.max(multiplied, properties.minRouteCandidates()), finalTopK);
        return (int) Math.min(minimumApplied, properties.maxRouteCandidates());
    }
}
