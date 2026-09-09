package com.wude.nexusmind.rag.context;

public record RagSource(
        String sourceId,
        long chunkId,
        long documentId,
        String fileName,
        Integer pageNo,
        String sectionTitle,
        float score,
        String content
) {
}
