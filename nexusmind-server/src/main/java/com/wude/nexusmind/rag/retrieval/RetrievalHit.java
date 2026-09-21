package com.wude.nexusmind.rag.retrieval;

import java.util.List;

public record RetrievalHit(
        long chunkId,
        long documentId,
        String fileName,
        int chunkIndex,
        double score,
        // 检索分数类型
        RetrievalScoreType scoreType,
        String content,
        Integer pageNo,
        String sectionTitle,
        List<RetrievalContribution> contributions,
        RerankProvenance rerank) {

    public RetrievalHit {
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }

    public RetrievalHit(long chunkId,
                        long documentId,
                        String fileName,
                        int chunkIndex,
                        double score,
                        RetrievalScoreType scoreType,
                        String content,
                        Integer pageNo,
                        String sectionTitle) {
        this(chunkId, documentId, fileName, chunkIndex, score, scoreType,
                content, pageNo, sectionTitle, List.of());
    }

    public RetrievalHit(long chunkId,
                        long documentId,
                        String fileName,
                        int chunkIndex,
                        double score,
                        RetrievalScoreType scoreType,
                        String content,
                        Integer pageNo,
                        String sectionTitle,
                        List<RetrievalContribution> contributions) {
        this(chunkId, documentId, fileName, chunkIndex, score, scoreType,
                content, pageNo, sectionTitle, contributions, null);
    }
}
