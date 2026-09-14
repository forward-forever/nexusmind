package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.config.ChunkingProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalContribution;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import com.wude.nexusmind.rag.retrieval.HybridRetrievalProperties;
import com.wude.nexusmind.rag.retrieval.RerankRetrievalProperties;
import com.wude.nexusmind.rag.retrieval.RerankProvenance;
import com.wude.nexusmind.model.config.RerankProviderProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RetrievalEvaluationServiceTest {

    @Test
    void evaluatesBm25ThroughRegistryWithoutDenseSpecificMetadataOrMetricLogic() {
        RetrievalService bm25 = new RetrievalService() {
            @Override
            public RetrieverType type() {
                return RetrieverType.BM25;
            }

            @Override
            public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
                return new RetrievalResult(
                        query,
                        knowledgeBaseId,
                        null,
                        0,
                        RetrieverType.BM25,
                        RetrievalScoreType.BM25,
                        topK,
                        List.of(new RetrievalHit(
                                101L, 10L, "fixture.txt", 0, 7.25f,
                                RetrievalScoreType.BM25, "content", null, null)));
            }
        };
        RetrievalDatasetValidator validator = mock(RetrievalDatasetValidator.class);
        RetrievalEvaluationService service = new RetrievalEvaluationService(
                new RetrievalServiceRegistry(List.of(bm25)),
                validator,
                new RetrievalMetricsCalculator(),
                new ChunkingProperties(500, 100),
                hybridProperties(),
                rerankProviderProperties(),
                new RerankRetrievalProperties(20, 50),
                Clock.systemUTC(),
                new IncrementingNanoTime());
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset(
                "same-golden",
                List.of(new RetrievalEvaluationCase(
                        "q1", 7L, "InnoDB 死锁", List.of(101L), QueryCategory.EXACT, null)));

        RetrievalEvaluationReport report = service.evaluate(dataset, RetrieverType.BM25);

        assertThat(report.metadata().retrieverType()).isEqualTo(RetrieverType.BM25);
        assertThat(report.metadata().scoreType()).isEqualTo(RetrievalScoreType.BM25);
        assertThat(report.metadata().hybrid()).isNull();
        assertThat(report.metadata().embeddingModel()).isNull();
        assertThat(report.metadata().embeddingDimension()).isZero();
        assertThat(report.metrics().at(1).hitRate()).isEqualTo(1.0);
        assertThat(report.cases()).singleElement().satisfies(result ->
                assertThat(result.retrieved()).singleElement().satisfies(hit -> {
                    assertThat(hit.score()).isEqualTo(7.25);
                    assertThat(hit.scoreType()).isEqualTo(RetrievalScoreType.BM25);
                }));
    }

    @Test
    void recordsReproducibleHybridMetadataAndRouteContributions() {
        RetrievalService hybrid = new RetrievalService() {
            @Override
            public RetrieverType type() {
                return RetrieverType.HYBRID_RRF;
            }

            @Override
            public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
                return new RetrievalResult(
                        query,
                        knowledgeBaseId,
                        "qwen3.7-text-embedding-flash",
                        1024,
                        RetrieverType.HYBRID_RRF,
                        RetrievalScoreType.RRF,
                        topK,
                        List.of(new RetrievalHit(
                                101L, 10L, "fixture.txt", 0, 2.0 / 61,
                                RetrievalScoreType.RRF, "content", null, null,
                                List.of(
                                        new RetrievalContribution(
                                                RetrieverType.DENSE, 1, 0.82,
                                                RetrievalScoreType.COSINE),
                                        new RetrievalContribution(
                                                RetrieverType.BM25, 1, 8.73,
                                                RetrievalScoreType.BM25)))));
            }
        };
        RetrievalEvaluationService service = new RetrievalEvaluationService(
                new RetrievalServiceRegistry(List.of(hybrid)),
                mock(RetrievalDatasetValidator.class),
                new RetrievalMetricsCalculator(),
                new ChunkingProperties(500, 100),
                hybridProperties(),
                rerankProviderProperties(),
                new RerankRetrievalProperties(20, 50),
                Clock.systemUTC(),
                new IncrementingNanoTime());
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset(
                "same-golden",
                List.of(new RetrievalEvaluationCase(
                        "q1", 7L, "InnoDB 死锁", List.of(101L), QueryCategory.EXACT, null)));

        RetrievalEvaluationReport report = service.evaluate(dataset, RetrieverType.HYBRID_RRF);

        assertThat(report.metadata().retrieverType()).isEqualTo(RetrieverType.HYBRID_RRF);
        assertThat(report.metadata().scoreType()).isEqualTo(RetrievalScoreType.RRF);
        assertThat(report.metadata().hybrid()).satisfies(metadata -> {
            assertThat(metadata.rrfK()).isEqualTo(60);
            assertThat(metadata.routes()).containsExactly(RetrieverType.DENSE, RetrieverType.BM25);
            assertThat(metadata.routeCandidateMultiplier()).isEqualTo(4);
            assertThat(metadata.minRouteCandidates()).isEqualTo(20);
            assertThat(metadata.maxRouteCandidates()).isEqualTo(60);
        });
        assertThat(report.cases().get(0).retrieved().get(0).contributions()).hasSize(2);
    }

    @Test
    void evaluatesHybridRerankThroughRegistryWithRerankAndUpstreamMetadata() {
        RetrievalService rerank = new RetrievalService() {
            @Override
            public RetrieverType type() {
                return RetrieverType.HYBRID_RERANK;
            }

            @Override
            public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
                return new RetrievalResult(
                        query,
                        knowledgeBaseId,
                        "qwen3.7-text-embedding-flash",
                        1024,
                        RetrieverType.HYBRID_RERANK,
                        RetrievalScoreType.RERANK,
                        topK,
                        List.of(new RetrievalHit(
                                101L, 10L, "fixture.txt", 0, 0.94,
                                RetrievalScoreType.RERANK, "content", null, null,
                                List.of(
                                        new RetrievalContribution(
                                                RetrieverType.DENSE, 2, 0.82,
                                                RetrievalScoreType.COSINE),
                                        new RetrievalContribution(
                                                RetrieverType.BM25, 8, 8.73,
                                                RetrievalScoreType.BM25)),
                                new RerankProvenance(5, 0.0308, RetrievalScoreType.RRF))));
            }
        };
        RetrievalEvaluationService service = new RetrievalEvaluationService(
                new RetrievalServiceRegistry(List.of(rerank)),
                mock(RetrievalDatasetValidator.class),
                new RetrievalMetricsCalculator(),
                new ChunkingProperties(500, 100),
                hybridProperties(),
                rerankProviderProperties(),
                new RerankRetrievalProperties(20, 50),
                Clock.systemUTC(),
                new IncrementingNanoTime());
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset(
                "same-golden",
                List.of(new RetrievalEvaluationCase(
                        "q1", 7L, "InnoDB 死锁", List.of(101L), QueryCategory.EXACT, null)));

        RetrievalEvaluationReport report = service.evaluate(dataset, RetrieverType.HYBRID_RERANK);

        assertThat(report.metadata().retrieverType()).isEqualTo(RetrieverType.HYBRID_RERANK);
        assertThat(report.metadata().scoreType()).isEqualTo(RetrievalScoreType.RERANK);
        assertThat(report.metadata().hybrid()).isNotNull();
        assertThat(report.metadata().rerank()).satisfies(metadata -> {
            assertThat(metadata.model()).isEqualTo("qwen3.7-text-rerank");
            assertThat(metadata.candidateTopN()).isEqualTo(20);
            assertThat(metadata.maxCandidateTopN()).isEqualTo(50);
            assertThat(metadata.upstreamRetriever()).isEqualTo(RetrieverType.HYBRID_RRF);
        });
        assertThat(report.cases().get(0).retrieved().get(0).rerank())
                .isEqualTo(new RerankProvenance(5, 0.0308, RetrievalScoreType.RRF));
    }

    private static HybridRetrievalProperties hybridProperties() {
        return new HybridRetrievalProperties(
                new HybridRetrievalProperties.Rrf(60), 4, 20, 60);
    }

    private static RerankProviderProperties rerankProviderProperties() {
        return new RerankProviderProperties(
                false, "qwen3.7-text-rerank", "", "", Duration.ofSeconds(3),
                "Given a web search query, retrieve relevant passages that answer the query.");
    }

    private static final class IncrementingNanoTime implements java.util.function.LongSupplier {
        private long value;

        @Override
        public long getAsLong() {
            long current = value;
            value += 1_000_000;
            return current;
        }
    }
}
