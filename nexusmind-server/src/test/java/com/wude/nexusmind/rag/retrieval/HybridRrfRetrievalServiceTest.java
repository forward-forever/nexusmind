package com.wude.nexusmind.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRrfRetrievalServiceTest {

    @Test
    void retrievesVisibleRoutesSequentiallyAtPlannedDepthAndFusesThem() {
        DenseRetrievalService dense = mock(DenseRetrievalService.class);
        Bm25RetrievalService bm25 = mock(Bm25RetrievalService.class);
        when(dense.retrieve(7L, "query", 20)).thenReturn(route(
                RetrieverType.DENSE, RetrievalScoreType.COSINE, "embedding", 1024,
                List.of(hit(1L, 0.8, RetrievalScoreType.COSINE))));
        when(bm25.retrieve(7L, "query", 20)).thenReturn(route(
                RetrieverType.BM25, RetrievalScoreType.BM25, null, 0,
                List.of(hit(2L, 8.0, RetrievalScoreType.BM25))));

        RetrievalResult result = service(dense, bm25).retrieve(7L, "query", 5);

        assertThat(result.retrieverType()).isEqualTo(RetrieverType.HYBRID_RRF);
        assertThat(result.scoreType()).isEqualTo(RetrievalScoreType.RRF);
        assertThat(result.model()).isEqualTo("embedding");
        assertThat(result.dimension()).isEqualTo(1024);
        assertThat(result.hits()).extracting(RetrievalHit::chunkId).containsExactly(1L, 2L);
        InOrder order = inOrder(dense, bm25);
        order.verify(dense).retrieve(7L, "query", 20);
        order.verify(bm25).retrieve(7L, "query", 20);
    }

    @Test
    void returnsEmptyWhenBothVisibleRoutesAreEmpty() {
        DenseRetrievalService dense = mock(DenseRetrievalService.class);
        Bm25RetrievalService bm25 = mock(Bm25RetrievalService.class);
        when(dense.retrieve(7L, "query", 20)).thenReturn(route(
                RetrieverType.DENSE, RetrievalScoreType.COSINE, "embedding", 1024, List.of()));
        when(bm25.retrieve(7L, "query", 20)).thenReturn(route(
                RetrieverType.BM25, RetrievalScoreType.BM25, null, 0, List.of()));

        assertThat(service(dense, bm25).retrieve(7L, "query", 5).hits()).isEmpty();
    }

    @Test
    void failsFastWithoutRunningSecondRouteWhenDenseRouteFails() {
        DenseRetrievalService dense = mock(DenseRetrievalService.class);
        Bm25RetrievalService bm25 = mock(Bm25RetrievalService.class);
        when(dense.retrieve(7L, "query", 20)).thenThrow(new IllegalStateException("embedding failed"));

        assertThatThrownBy(() -> service(dense, bm25).retrieve(7L, "query", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding failed");
        verify(bm25, never()).retrieve(7L, "query", 20);
    }

    private static HybridRrfRetrievalService service(DenseRetrievalService dense,
                                                      Bm25RetrievalService bm25) {
        HybridRetrievalProperties properties = new HybridRetrievalProperties(
                new HybridRetrievalProperties.Rrf(60), 4, 20, 60);
        return new HybridRrfRetrievalService(
                dense,
                bm25,
                new HybridRouteCandidatePlanner(properties),
                new ReciprocalRankFusion(),
                properties);
    }

    private static RetrievalResult route(RetrieverType type,
                                         RetrievalScoreType scoreType,
                                         String model,
                                         int dimension,
                                         List<RetrievalHit> hits) {
        return new RetrievalResult("query", 7L, model, dimension, type, scoreType, 20, hits);
    }

    private static RetrievalHit hit(long chunkId, double score, RetrievalScoreType scoreType) {
        return new RetrievalHit(
                chunkId, 10L, "fixture.txt", 0, score, scoreType,
                "content-" + chunkId, null, null);
    }
}
