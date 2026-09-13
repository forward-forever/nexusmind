package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.config.ChunkingProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.function.LongSupplier;

public class RetrievalEvaluationService {

    public static final int MAX_TOP_K = 10;

    private final RetrievalServiceRegistry retrievalServiceRegistry;
    private final RetrievalDatasetValidator datasetValidator;
    private final RetrievalMetricsCalculator metricsCalculator;
    private final ChunkingProperties chunkingProperties;
    private final Clock clock;
    private final LongSupplier nanoTime;

    public RetrievalEvaluationService(RetrievalServiceRegistry retrievalServiceRegistry,
                                      RetrievalDatasetValidator datasetValidator,
                                      RetrievalMetricsCalculator metricsCalculator,
                                      ChunkingProperties chunkingProperties,
                                      Clock clock,
                                      LongSupplier nanoTime) {
        this.retrievalServiceRegistry = retrievalServiceRegistry;
        this.datasetValidator = datasetValidator;
        this.metricsCalculator = metricsCalculator;
        this.chunkingProperties = chunkingProperties;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    public RetrievalEvaluationReport evaluate(RetrievalEvaluationDataset dataset,
                                               RetrieverType retrieverType) {
        datasetValidator.validate(dataset);
        RetrievalService retrievalService = retrievalServiceRegistry.get(retrieverType);
        List<RetrievalEvaluationCaseResult> caseResults = new ArrayList<>(dataset.cases().size());
        RetrievalResult baselineIdentity = null;
        for (RetrievalEvaluationCase evaluationCase : dataset.cases()) {
            long startedAt = nanoTime.getAsLong();
            RetrievalResult result = retrievalService.retrieve(
                    evaluationCase.knowledgeBaseId(), evaluationCase.question(), MAX_TOP_K);
            long latencyMs = Math.max(0, (nanoTime.getAsLong() - startedAt) / 1_000_000);
            validateResultIdentity(evaluationCase, result, baselineIdentity, retrieverType);
            if (baselineIdentity == null) {
                baselineIdentity = result;
            }
            caseResults.add(toCaseResult(evaluationCase, result, latencyMs));
        }

        RetrievalBaselineMetadata metadata = new RetrievalBaselineMetadata(
                Instant.now(clock).toString(),
                dataset.name(),
                dataset.cases().size(),
                baselineIdentity.retrieverType(),
                MAX_TOP_K,
                baselineIdentity.model(),
                baselineIdentity.dimension(),
                chunkingProperties.chunkSizeChars(),
                chunkingProperties.chunkOverlapChars(),
                baselineIdentity.scoreType());
        List<RetrievalEvaluationCaseResult> failures = caseResults.stream()
                .filter(result -> result.firstRelevantRank() == null || result.firstRelevantRank() > 5)
                .toList();
        return new RetrievalEvaluationReport(
                metadata,
                metricsCalculator.calculate(caseResults),
                metricsCalculator.latency(caseResults),
                metricsCalculator.categoryBreakdown(caseResults),
                caseResults,
                failures);
    }

    private static RetrievalEvaluationCaseResult toCaseResult(
            RetrievalEvaluationCase evaluationCase,
            RetrievalResult result,
            long latencyMs) {
        List<RetrievalHit> hits = result.hits().stream().limit(MAX_TOP_K).toList();
        List<RetrievalEvaluationCaseResult.RetrievedChunk> retrieved = new ArrayList<>(hits.size());
        List<Integer> hitRanks = new ArrayList<>();
        Set<Long> relevant = Set.copyOf(evaluationCase.relevantChunkIds());
        Set<Long> matchedRelevant = new HashSet<>();
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHit hit = hits.get(index);
            int rank = index + 1;
            retrieved.add(new RetrievalEvaluationCaseResult.RetrievedChunk(
                    hit.chunkId(), rank, hit.score(), hit.scoreType()));
            if (relevant.contains(hit.chunkId()) && matchedRelevant.add(hit.chunkId())) {
                hitRanks.add(rank);
            }
        }
        Integer firstRelevantRank = hitRanks.isEmpty() ? null : hitRanks.get(0);
        return new RetrievalEvaluationCaseResult(
                evaluationCase.id(),
                evaluationCase.knowledgeBaseId(),
                evaluationCase.question(),
                evaluationCase.category(),
                evaluationCase.relevantChunkIds(),
                retrieved,
                hitRanks,
                firstRelevantRank,
                latencyMs);
    }

    private static void validateResultIdentity(RetrievalEvaluationCase evaluationCase,
                                               RetrievalResult result,
                                               RetrievalResult baseline,
                                               RetrieverType requestedRetriever) {
        if (result.knowledgeBaseId() != evaluationCase.knowledgeBaseId()) {
            throw new IllegalStateException("Retriever returned a result for the wrong knowledge base");
        }
        if (result.topK() != MAX_TOP_K) {
            throw new IllegalStateException("Retriever did not preserve requested topK=" + MAX_TOP_K);
        }
        if (result.retrieverType() != requestedRetriever) {
            throw new IllegalStateException(
                    "Retriever returned identity %s for requested %s"
                            .formatted(result.retrieverType(), requestedRetriever));
        }
        if (baseline != null
                && (result.retrieverType() != baseline.retrieverType()
                || result.scoreType() != baseline.scoreType()
                || !Objects.equals(result.model(), baseline.model())
                || result.dimension() != baseline.dimension())) {
            throw new IllegalStateException("Retriever identity changed within one evaluation run");
        }
    }
}
