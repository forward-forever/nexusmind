package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.rag.retrieval.RetrieverType;

public record RerankEvaluationMetadata(
        String model,
        int candidateTopN,
        int maxCandidateTopN,
        RetrieverType upstreamRetriever) {
}
