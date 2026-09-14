package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.model.config.RerankProviderProperties;
import com.wude.nexusmind.rag.rerank.RerankClient;
import com.wude.nexusmind.rag.rerank.RerankResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRerankRetrievalServiceTest {

    @Test
    void mapsProviderIndexesAndPreservesAllUpstreamProvenance() {
        HybridRrfRetrievalService hybrid = mock(HybridRrfRetrievalService.class);
        RerankClient client = mock(RerankClient.class);
        List<RetrievalHit> candidates = List.of(
                hybridHit(101, 0.032, 1, 2),
                hybridHit(102, 0.031, 2, 1),
                hybridHit(103, 0.030, 5, 8));
        when(hybrid.retrieve(7L, "query", 20)).thenReturn(upstream(candidates, 20));
        when(client.rerank("query", candidates.stream().map(RetrievalHit::content).toList(), 3))
                .thenReturn(new RerankResult(
                        List.of(
                                new RerankResult.Item(2, 0.95),
                                new RerankResult.Item(0, 0.80),
                                new RerankResult.Item(1, 0.40)),
                        new RerankResult.Usage(88L, 88L),
                        27));

        RetrievalResult result = service(hybrid, client).retrieve(7L, "query", 3);

        assertThat(result.retrieverType()).isEqualTo(RetrieverType.HYBRID_RERANK);
        assertThat(result.scoreType()).isEqualTo(RetrievalScoreType.RERANK);
        assertThat(result.hits()).extracting(RetrievalHit::chunkId)
                .containsExactly(103L, 101L, 102L);
        RetrievalHit first = result.hits().get(0);
        assertThat(first.score()).isEqualTo(0.95);
        assertThat(first.rerank()).isEqualTo(
                new RerankProvenance(3, 0.030, RetrievalScoreType.RRF));
        assertThat(first.contributions()).containsExactlyElementsOf(candidates.get(2).contributions());
        assertThat(result.rerank()).satisfies(metadata -> {
            assertThat(metadata.model()).isEqualTo("qwen3.7-text-rerank");
            assertThat(metadata.candidateCount()).isEqualTo(3);
            assertThat(metadata.requestedTopN()).isEqualTo(3);
            assertThat(metadata.latencyMs()).isEqualTo(27);
            assertThat(metadata.usage().totalTokens()).isEqualTo(88);
        });
    }

    @Test
    void rerankProviderOrderWinsRegardlessOfUpstreamRawScores() {
        HybridRrfRetrievalService hybrid = mock(HybridRrfRetrievalService.class);
        RerankClient client = mock(RerankClient.class);
        List<RetrievalHit> candidates = List.of(
                hybridHit(101, 0.99, 1, 1),
                hybridHit(102, 0.01, 20, 20));
        when(hybrid.retrieve(7L, "query", 20)).thenReturn(upstream(candidates, 20));
        when(client.rerank("query", List.of("content-101", "content-102"), 2))
                .thenReturn(result(new RerankResult.Item(1, 0.9), new RerankResult.Item(0, 0.1)));

        assertThat(service(hybrid, client).retrieve(7L, "query", 2).hits())
                .extracting(RetrievalHit::chunkId)
                .containsExactly(102L, 101L);
    }

    @Test
    void usesPreRerankRankThenChunkIdAsDeterministicTieBreak() {
        HybridRrfRetrievalService hybrid = mock(HybridRrfRetrievalService.class);
        RerankClient client = mock(RerankClient.class);
        List<RetrievalHit> candidates = List.of(
                hybridHit(103, 0.03, 1, 1),
                hybridHit(101, 0.03, 2, 2));
        when(hybrid.retrieve(7L, "query", 20)).thenReturn(upstream(candidates, 20));
        when(client.rerank("query", List.of("content-103", "content-101"), 2))
                .thenReturn(result(new RerankResult.Item(1, 0.5), new RerankResult.Item(0, 0.5)));

        RetrievalResult first = service(hybrid, client).retrieve(7L, "query", 2);
        RetrievalResult second = service(hybrid, client).retrieve(7L, "query", 2);

        assertThat(first.hits()).extracting(RetrievalHit::chunkId).containsExactly(103L, 101L);
        assertThat(second.hits()).extracting(RetrievalHit::chunkId).containsExactly(103L, 101L);
    }

    @ParameterizedTest
    @MethodSource("invalidProviderResults")
    void rejectsInvalidProviderProtocol(RerankResult invalidResult, String message) {
        HybridRrfRetrievalService hybrid = mock(HybridRrfRetrievalService.class);
        RerankClient client = mock(RerankClient.class);
        List<RetrievalHit> candidates = List.of(
                hybridHit(101, 0.03, 1, 1), hybridHit(102, 0.02, 2, 2));
        when(hybrid.retrieve(7L, "query", 20)).thenReturn(upstream(candidates, 20));
        when(client.rerank("query", List.of("content-101", "content-102"), 2))
                .thenReturn(invalidResult);

        assertThatThrownBy(() -> service(hybrid, client).retrieve(7L, "query", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    @Test
    void failsFastWhenProviderFailsWithoutReturningUpstreamHybrid() {
        HybridRrfRetrievalService hybrid = mock(HybridRrfRetrievalService.class);
        RerankClient client = mock(RerankClient.class);
        when(hybrid.retrieve(7L, "query", 20)).thenReturn(
                upstream(List.of(hybridHit(101, 0.03, 1, 1)), 20));
        when(client.rerank("query", List.of("content-101"), 1))
                .thenThrow(new RuntimeException(new TimeoutException("timeout")));

        assertThatThrownBy(() -> service(hybrid, client).retrieve(7L, "query", 1))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void emptyUpstreamReturnsEmptyRerankIdentityWithoutCallingProvider() {
        HybridRrfRetrievalService hybrid = mock(HybridRrfRetrievalService.class);
        RerankClient client = mock(RerankClient.class);
        when(hybrid.retrieve(7L, "query", 20)).thenReturn(upstream(List.of(), 20));

        RetrievalResult result = service(hybrid, client).retrieve(7L, "query", 5);

        assertThat(result.hits()).isEmpty();
        assertThat(result.retrieverType()).isEqualTo(RetrieverType.HYBRID_RERANK);
        assertThat(result.rerank().candidateCount()).isZero();
        verify(client, never()).rerank(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void requestsConfiguredCandidateTopNAndFinalTopKSeparately() {
        HybridRrfRetrievalService hybrid = mock(HybridRrfRetrievalService.class);
        RerankClient client = mock(RerankClient.class);
        List<RetrievalHit> candidates = List.of(hybridHit(101, 0.03, 1, 1));
        when(hybrid.retrieve(7L, "query", 20)).thenReturn(upstream(candidates, 20));
        when(client.rerank(org.mockito.ArgumentMatchers.eq("query"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(5)))
                .thenReturn(result(new RerankResult.Item(0, 0.9)));

        service(hybrid, client).retrieve(7L, "query", 5);

        verify(hybrid).retrieve(7L, "query", 20);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(client).rerank(org.mockito.ArgumentMatchers.eq("query"), documents.capture(),
                org.mockito.ArgumentMatchers.eq(5));
        assertThat(documents.getValue()).containsExactly("content-101");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidProviderResults() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        result(new RerankResult.Item(-1, 0.5)), "out-of-range"),
                org.junit.jupiter.params.provider.Arguments.of(
                        result(new RerankResult.Item(2, 0.5)), "out-of-range"),
                org.junit.jupiter.params.provider.Arguments.of(
                        result(new RerankResult.Item(0, 0.5), new RerankResult.Item(0, 0.4)),
                        "duplicate"),
                org.junit.jupiter.params.provider.Arguments.of(
                        result(new RerankResult.Item(0, Double.NaN)), "invalid relevance score"),
                org.junit.jupiter.params.provider.Arguments.of(
                        new RerankResult(List.of(), null, 1), "no ranking results"));
    }

    private static HybridRerankRetrievalService service(HybridRrfRetrievalService hybrid,
                                                         RerankClient client) {
        RerankProviderProperties provider = new RerankProviderProperties(
                true, "qwen3.7-text-rerank", "https://workspace.example/api/v1",
                "fake-key", Duration.ofSeconds(3),
                "Given a web search query, retrieve relevant passages that answer the query.");
        RerankRetrievalProperties retrieval = new RerankRetrievalProperties(20, 50);
        return new HybridRerankRetrievalService(
                hybrid, client, new RerankCandidatePlanner(retrieval), provider);
    }

    private static RetrievalResult upstream(List<RetrievalHit> candidates, int topK) {
        return new RetrievalResult(
                "query", 7L, "embedding-model", 1024,
                RetrieverType.HYBRID_RRF, RetrievalScoreType.RRF, topK, candidates);
    }

    private static RetrievalHit hybridHit(long chunkId,
                                          double rrfScore,
                                          int denseRank,
                                          int bm25Rank) {
        return new RetrievalHit(
                chunkId, 10L, "fixture.pdf", (int) chunkId, rrfScore,
                RetrievalScoreType.RRF, "content-" + chunkId, 4, "section",
                List.of(
                        new RetrievalContribution(
                                RetrieverType.DENSE, denseRank, 0.8,
                                RetrievalScoreType.COSINE),
                        new RetrievalContribution(
                                RetrieverType.BM25, bm25Rank, 8.0,
                                RetrievalScoreType.BM25)));
    }

    private static RerankResult result(RerankResult.Item... items) {
        return new RerankResult(List.of(items), null, 1);
    }
}
