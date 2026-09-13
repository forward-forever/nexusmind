package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrieverType;

public record RetrievalBaselineMetadata(
        String timestamp,
        String datasetName,
        int datasetQueryCount,
        RetrieverType retrieverType,
        int topKMax,
        String embeddingModel,
        int embeddingDimension,
        int chunkSizeChars,
        int chunkOverlapChars,
        RetrievalScoreType metric) {
}
