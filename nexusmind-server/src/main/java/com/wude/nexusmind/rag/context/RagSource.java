package com.wude.nexusmind.rag.context;

import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;

public record RagSource(
        String sourceId,
        long chunkId,
        long documentId,
        String fileName,
        Integer pageNo,
        String sectionTitle,
        float score,
        RetrievalScoreType scoreType,
        String content
) {
}
