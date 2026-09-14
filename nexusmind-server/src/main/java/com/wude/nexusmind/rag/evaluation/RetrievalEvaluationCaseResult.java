package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrievalContribution;

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
            double score,
            RetrievalScoreType scoreType,
            List<RetrievalContribution> contributions) {

        public RetrievedChunk {
            contributions = List.copyOf(contributions);
        }
    }
}
