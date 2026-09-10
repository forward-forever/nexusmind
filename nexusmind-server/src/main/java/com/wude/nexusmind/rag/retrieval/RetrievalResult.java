package com.wude.nexusmind.rag.retrieval;

import java.util.List;

public record RetrievalResult(
        String query,
        long knowledgeBaseId,
        String model,
        int dimension,
        RetrievalScoreType scoreType,
        int topK,
        List<RetrievalHit> hits) {

    public RetrievalResult {
        hits = List.copyOf(hits);
    }
}
