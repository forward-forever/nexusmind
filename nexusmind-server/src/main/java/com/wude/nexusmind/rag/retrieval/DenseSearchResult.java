package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.rag.milvus.DenseVectorHit;

import java.util.List;

public record DenseSearchResult(
        String query,
        long knowledgeBaseId,
        String model,
        int dimension,
        String metric,
        int topK,
        List<DenseVectorHit> results
) {
    public DenseSearchResult {
        results = List.copyOf(results);
    }
}
