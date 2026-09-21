package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.model.config.RerankProviderProperties;
import com.wude.nexusmind.rag.rerank.RerankClient;
import com.wude.nexusmind.rag.rerank.RerankResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hybrid retrieval service that first uses RRF and then reranks the results using a language model.
 */
public class HybridRerankRetrievalService implements RetrievalService {

    private final HybridRrfRetrievalService hybridRetrievalService;
    private final RerankClient rerankClient;
    private final RerankCandidatePlanner candidatePlanner;
    private final RerankProviderProperties providerProperties;

    public HybridRerankRetrievalService(HybridRrfRetrievalService hybridRetrievalService,
                                        RerankClient rerankClient,
                                        RerankCandidatePlanner candidatePlanner,
                                        RerankProviderProperties providerProperties) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankClient = rerankClient;
        this.candidatePlanner = candidatePlanner;
        this.providerProperties = providerProperties;
    }

    @Override
    public RetrieverType type() {
        return RetrieverType.HYBRID_RERANK;
    }

    @Override
    public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query is required");
        }
        int candidateTopN = candidatePlanner.plan(topK);
        RetrievalResult upstream = hybridRetrievalService.retrieve(
                knowledgeBaseId, query, candidateTopN);
        validateUpstream(upstream, knowledgeBaseId, candidateTopN);

        if (upstream.hits().isEmpty()) {
            return result(query, knowledgeBaseId, topK, upstream, List.of(),
                    new RerankExecutionMetadata(
                            providerProperties.model(), 0, topK, 0L, null));
        }

        List<RetrievalHit> candidates = upstream.hits();
        RerankResult reranked = rerankClient.rerank(
                query,
                candidates.stream().map(RetrievalHit::content).toList(),
                topK);
        List<RankedCandidate> ranked = validateAndMap(reranked, candidates, topK);
        List<RetrievalHit> hits = ranked.stream()
                .sorted(Comparator.comparingDouble(RankedCandidate::score).reversed() // rerank分数
                        // Break ties by pre-rerank rank
                        .thenComparingInt(RankedCandidate::preRerankRank)
                        // Break ties by chunk ID
                        .thenComparingLong(candidate -> candidate.hit().chunkId()))
                .map(RankedCandidate::toRetrievalHit)
                .toList();
        return result(query, knowledgeBaseId, topK, upstream, hits,
                new RerankExecutionMetadata(
                        providerProperties.model(), candidates.size(), topK,
                        reranked.latencyMs(), reranked.usage()));
    }

    private static RetrievalResult result(String query,
                                          long knowledgeBaseId,
                                          int topK,
                                          RetrievalResult upstream,
                                          List<RetrievalHit> hits,
                                          RerankExecutionMetadata rerank) {
        return new RetrievalResult(
                query,
                knowledgeBaseId,
                upstream.model(),
                upstream.dimension(),
                RetrieverType.HYBRID_RERANK,
                RetrievalScoreType.RERANK,
                topK,
                hits,
                rerank);
    }

    private static List<RankedCandidate> validateAndMap(RerankResult result,
                                                        List<RetrievalHit> candidates,
                                                        int requestedTopN) {
        if (result == null || result.items() == null || result.items().isEmpty()) {
            throw new IllegalStateException("Rerank provider returned no ranking results");
        }
        if (result.items().size() > requestedTopN) {
            throw new IllegalStateException(
                    "Rerank provider returned more results than requested topN=" + requestedTopN);
        }

        Set<Integer> indexes = new HashSet<>();
        List<RankedCandidate> mapped = new ArrayList<>(result.items().size());
        for (RerankResult.Item item : result.items()) {
            if (item.index() < 0 || item.index() >= candidates.size()) {
                throw new IllegalStateException(
                        "Rerank provider returned out-of-range candidate index " + item.index());
            }
            if (!indexes.add(item.index())) {
                throw new IllegalStateException(
                        "Rerank provider returned duplicate candidate index " + item.index());
            }
            if (!Double.isFinite(item.relevanceScore())
                    || item.relevanceScore() < 0.0d
                    || item.relevanceScore() > 1.0d) {
                throw new IllegalStateException(
                        "Rerank provider returned invalid relevance score for candidate index "
                                + item.index());
            }
            RetrievalHit candidate = candidates.get(item.index());
            mapped.add(new RankedCandidate(candidate, item.index() + 1, item.relevanceScore()));
        }
        return mapped;
    }

    private static void validateUpstream(RetrievalResult upstream,
                                         long knowledgeBaseId,
                                         int candidateTopN) {
        if (upstream.retrieverType() != RetrieverType.HYBRID_RRF
                || upstream.scoreType() != RetrievalScoreType.RRF
                || upstream.knowledgeBaseId() != knowledgeBaseId
                || upstream.topK() != candidateTopN) {
            throw new IllegalStateException("Invalid HYBRID_RRF upstream result identity");
        }
    }

    private record RankedCandidate(RetrievalHit hit, int preRerankRank, double score) {

        private RetrievalHit toRetrievalHit() {
            return new RetrievalHit(
                    hit.chunkId(),
                    hit.documentId(),
                    hit.fileName(),
                    hit.chunkIndex(),
                    score,
                    RetrievalScoreType.RERANK,
                    hit.content(),
                    hit.pageNo(),
                    hit.sectionTitle(),
                    hit.contributions(),
                    new RerankProvenance(preRerankRank, hit.score(), hit.scoreType()));
        }
    }
}
