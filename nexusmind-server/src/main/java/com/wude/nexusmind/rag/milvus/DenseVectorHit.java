package com.wude.nexusmind.rag.milvus;

public record DenseVectorHit(
        long chunkId,
        long documentId,
        int chunkIndex,
        float score,
        String content,
        Integer pageNo,
        String sectionTitle
) {
}
