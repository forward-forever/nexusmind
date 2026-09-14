package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.rag.retrieval.RetrieverType;

import java.util.List;

public record HybridEvaluationMetadata(
        int rrfK,
        List<RetrieverType> routes,
        int routeCandidateMultiplier,
        int minRouteCandidates,
        int maxRouteCandidates) {

    public HybridEvaluationMetadata {
        routes = List.copyOf(routes);
    }
}
