package com.wude.nexusmind.rag.retrieval;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Hybrid RRF Retrieval Service. 混合检索服务
 */
public class HybridRrfRetrievalService implements RetrievalService {

    private final DenseRetrievalService denseRetrievalService;
    private final Bm25RetrievalService bm25RetrievalService;
    private final HybridRouteCandidatePlanner candidatePlanner;
    private final ReciprocalRankFusion fusion;
    private final HybridRetrievalProperties properties;
    private final Executor routeExecutor;

    public HybridRrfRetrievalService(DenseRetrievalService denseRetrievalService,
                                     Bm25RetrievalService bm25RetrievalService,
                                     HybridRouteCandidatePlanner candidatePlanner,
                                     ReciprocalRankFusion fusion,
                                     HybridRetrievalProperties properties) {
        this(denseRetrievalService, bm25RetrievalService, candidatePlanner, fusion,
                properties, Runnable::run);
    }

    public HybridRrfRetrievalService(DenseRetrievalService denseRetrievalService,
                                     Bm25RetrievalService bm25RetrievalService,
                                     HybridRouteCandidatePlanner candidatePlanner,
                                     ReciprocalRankFusion fusion,
                                     HybridRetrievalProperties properties,
                                     Executor routeExecutor) {
        this.denseRetrievalService = denseRetrievalService;
        this.bm25RetrievalService = bm25RetrievalService;
        this.candidatePlanner = candidatePlanner;
        this.fusion = fusion;
        this.properties = properties;
        this.routeExecutor = routeExecutor;
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

        CompletableFuture<RetrievalResult> denseFuture = null;
        CompletableFuture<RetrievalResult> bm25Future = null;
        try {
            denseFuture = CompletableFuture.supplyAsync(
                    () -> denseRetrievalService.retrieve(knowledgeBaseId, query, routeCandidateK),
                    routeExecutor);
            bm25Future = CompletableFuture.supplyAsync(
                    () -> bm25RetrievalService.retrieve(knowledgeBaseId, query, routeCandidateK),
                    routeExecutor);
        } catch (RuntimeException schedulingFailure) {
            if (denseFuture != null) {
                denseFuture.cancel(true);
            }
            throw schedulingFailure;
        }

        RetrievalResult dense;
        RetrievalResult bm25;
        try {
            dense = denseFuture.join();
            bm25 = bm25Future.join();
        } catch (CompletionException failure) {
            denseFuture.cancel(true);
            bm25Future.cancel(true);
            throw propagate(failure.getCause());
        }
        validateRoute(dense, RetrieverType.DENSE, RetrievalScoreType.COSINE, knowledgeBaseId);
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

    private static RuntimeException propagate(Throwable failure) {
        return failure instanceof RuntimeException runtime
                ? runtime : new IllegalStateException("Hybrid retrieval route failed", failure);
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
