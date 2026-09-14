package com.wude.nexusmind.rag.retrieval;

import java.util.List;

/**
 * Hybrid RRF Retrieval Service. 混合检索服务
 */
public class HybridRrfRetrievalService implements RetrievalService {

    private final DenseRetrievalService denseRetrievalService;
    private final Bm25RetrievalService bm25RetrievalService;
    private final HybridRouteCandidatePlanner candidatePlanner;
    private final ReciprocalRankFusion fusion;
    private final HybridRetrievalProperties properties;

    public HybridRrfRetrievalService(DenseRetrievalService denseRetrievalService,
                                     Bm25RetrievalService bm25RetrievalService,
                                     HybridRouteCandidatePlanner candidatePlanner,
                                     ReciprocalRankFusion fusion,
                                     HybridRetrievalProperties properties) {
        this.denseRetrievalService = denseRetrievalService;
        this.bm25RetrievalService = bm25RetrievalService;
        this.candidatePlanner = candidatePlanner;
        this.fusion = fusion;
        this.properties = properties;
    }

    @Override
    public RetrieverType type() {
        return RetrieverType.HYBRID_RRF;
    }

    @Override
    public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query is required");
        }
        int routeCandidateK = candidatePlanner.plan(topK);

        RetrievalResult dense = denseRetrievalService.retrieve(knowledgeBaseId, query, routeCandidateK);
        validateRoute(dense, RetrieverType.DENSE, RetrievalScoreType.COSINE, knowledgeBaseId);
        RetrievalResult bm25 = bm25RetrievalService.retrieve(knowledgeBaseId, query, routeCandidateK);
        validateRoute(bm25, RetrieverType.BM25, RetrievalScoreType.BM25, knowledgeBaseId);

        List<RetrievalHit> fused = fusion.fuse(
                dense.hits(), bm25.hits(), properties.rrf().k(), topK);
        return new RetrievalResult(
                query,
                knowledgeBaseId,
                dense.model(),
                dense.dimension(),
                RetrieverType.HYBRID_RRF,
                RetrievalScoreType.RRF,
                topK,
                fused);
    }

    private static void validateRoute(RetrievalResult result,
                                      RetrieverType expectedRetriever,
                                      RetrievalScoreType expectedScoreType,
                                      long knowledgeBaseId) {
        if (result.retrieverType() != expectedRetriever
                || result.scoreType() != expectedScoreType
                || result.knowledgeBaseId() != knowledgeBaseId) {
            throw new IllegalStateException(
                    "Invalid %s route result identity".formatted(expectedRetriever));
        }
    }
}
