package com.wude.nexusmind.rag.retrieval;

public record RetrievalHit(
        long chunkId,
        long documentId,
        String fileName,
        int chunkIndex,
        float score,
        RetrievalScoreType scoreType,
        String content,
        Integer pageNo,
        String sectionTitle) {
}
