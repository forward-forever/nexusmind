package com.wude.nexusmind.rag.retrieval;

public class RerankCandidatePlanner {

    private final RerankRetrievalProperties properties;

    public RerankCandidatePlanner(RerankRetrievalProperties properties) {
        this.properties = properties;
    }

    public int plan(int finalTopK) {
        if (finalTopK <= 0 || finalTopK > properties.maxCandidateTopN()) {
            throw new IllegalArgumentException(
                    "Rerank topK must be between 1 and " + properties.maxCandidateTopN());
        }
        return Math.max(properties.candidateTopN(), finalTopK);
    }
}
