package com.wude.nexusmind.rag.api;

import com.wude.nexusmind.rag.retrieval.RerankExecutionMetadata;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrieverType;

import java.util.List;

public record RetrievalSearchResult(
        String query,
        long knowledgeBaseId,
        String model,
        int dimension,
        RetrieverType retrieverType,
        String metric,
        int topK,
        List<RetrievalHit> results,
        RerankExecutionMetadata rerank
) {
    public RetrievalSearchResult {
        results = List.copyOf(results);
    }

    public static RetrievalSearchResult from(RetrievalResult result) {
        return new RetrievalSearchResult(
                result.query(),
                result.knowledgeBaseId(),
                result.model(),
                result.dimension(),
                result.retrieverType(),
                result.scoreType().name(),
                result.topK(),
                result.hits(),
                result.rerank());
    }
}
