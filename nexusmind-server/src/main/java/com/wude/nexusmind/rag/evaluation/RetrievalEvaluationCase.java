package com.wude.nexusmind.rag.evaluation;

import java.util.List;

public record RetrievalEvaluationCase(
        String id,
        long knowledgeBaseId,
        String question,
        List<Long> relevantChunkIds,
        QueryCategory category,
        String note) {

    public RetrievalEvaluationCase {
        relevantChunkIds = relevantChunkIds == null ? null : List.copyOf(relevantChunkIds);
    }
}
