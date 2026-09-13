package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;

import java.util.List;

public record RetrievalEvaluationCaseResult(
        String queryId,
        long knowledgeBaseId,
        String question,
        QueryCategory category,
        List<Long> relevantChunkIds,
        List<RetrievedChunk> retrieved,
        List<Integer> hitRanks,
        Integer firstRelevantRank,
        long latencyMs) {

    public RetrievalEvaluationCaseResult {
        relevantChunkIds = List.copyOf(relevantChunkIds);
        retrieved = List.copyOf(retrieved);
        hitRanks = List.copyOf(hitRanks);
    }

    public record RetrievedChunk(
            long chunkId,
            int rank,
            float score,
            RetrievalScoreType scoreType) {
    }
}
