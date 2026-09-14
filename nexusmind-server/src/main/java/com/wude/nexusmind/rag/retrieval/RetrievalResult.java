package com.wude.nexusmind.rag.retrieval;

import java.util.List;

public record RetrievalResult(
        String query,
        long knowledgeBaseId,
        String model,
        int dimension,
        RetrieverType retrieverType,
        RetrievalScoreType scoreType,
        int topK,
        List<RetrievalHit> hits,
        RerankExecutionMetadata rerank) {

    public RetrievalResult {
        hits = List.copyOf(hits);
    }

    public RetrievalResult(String query,
                           long knowledgeBaseId,
                           String model,
                           int dimension,
                           RetrieverType retrieverType,
                           RetrievalScoreType scoreType,
                           int topK,
                           List<RetrievalHit> hits) {
        this(query, knowledgeBaseId, model, dimension, retrieverType,
                scoreType, topK, hits, null);
    }
}
