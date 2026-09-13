package com.wude.nexusmind.rag.milvus;

public record Bm25SparseHit(
        long chunkId,
        long documentId,
        int chunkIndex,
        float score,
        String content,
        Integer pageNo,
        String sectionTitle
) implements RetrievalCandidateHit {
}
