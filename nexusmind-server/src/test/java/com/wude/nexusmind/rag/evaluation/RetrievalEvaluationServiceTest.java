package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.config.ChunkingProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
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
                Clock.systemUTC(),
                new IncrementingNanoTime());
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset(
                "same-golden",
                List.of(new RetrievalEvaluationCase(
                        "q1", 7L, "InnoDB 死锁", List.of(101L), QueryCategory.EXACT, null)));

        RetrievalEvaluationReport report = service.evaluate(dataset, RetrieverType.BM25);

        assertThat(report.metadata().retrieverType()).isEqualTo(RetrieverType.BM25);
        assertThat(report.metadata().metric()).isEqualTo(RetrievalScoreType.BM25);
        assertThat(report.metadata().embeddingModel()).isNull();
        assertThat(report.metadata().embeddingDimension()).isZero();
        assertThat(report.metrics().at(1).hitRate()).isEqualTo(1.0);
        assertThat(report.cases()).singleElement().satisfies(result ->
                assertThat(result.retrieved()).singleElement().satisfies(hit -> {
                    assertThat(hit.score()).isEqualTo(7.25f);
                    assertThat(hit.scoreType()).isEqualTo(RetrievalScoreType.BM25);
                }));
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
