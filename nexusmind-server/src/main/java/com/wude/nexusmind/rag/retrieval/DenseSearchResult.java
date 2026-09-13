package com.wude.nexusmind.rag.retrieval;

import java.util.List;

public record DenseSearchResult(
        String query,
        long knowledgeBaseId,
        String model,
        int dimension,
        RetrieverType retrieverType,
        String metric,
        int topK,
        List<RetrievalHit> results
) {
    public DenseSearchResult {
        results = List.copyOf(results);
    }

    public static DenseSearchResult from(RetrievalResult result) {
        return new DenseSearchResult(
                result.query(),
                result.knowledgeBaseId(),
                result.model(),
                result.dimension(),
                result.retrieverType(),
                result.scoreType().name(),
                result.topK(),
                result.hits());
    }
}
