package com.wude.nexusmind.rag.api;

import com.wude.nexusmind.rag.context.RagSource;

public record RagSourceResponse(
        String id,
        long chunkId,
        long documentId,
        String fileName,
        Integer pageNo,
        String sectionTitle,
        float score
) {

    static RagSourceResponse from(RagSource source) {
        return new RagSourceResponse(
                source.sourceId(),
                source.chunkId(),
                source.documentId(),
                source.fileName(),
                source.pageNo(),
                source.sectionTitle(),
                source.score());
    }
}
