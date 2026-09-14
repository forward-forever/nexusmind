package com.wude.nexusmind.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    void calculatesStandardOneBasedRrfAndPreservesContributions() {
        List<RetrievalHit> dense = List.of(
                hit(1L, 0.91, RetrievalScoreType.COSINE),
                hit(2L, 0.82, RetrievalScoreType.COSINE),
                hit(3L, 0.73, RetrievalScoreType.COSINE));
        List<RetrievalHit> bm25 = List.of(
                hit(2L, 8.73, RetrievalScoreType.BM25),
                hit(4L, 7.10, RetrievalScoreType.BM25),
                hit(1L, 6.20, RetrievalScoreType.BM25));

        List<RetrievalHit> result = fusion.fuse(dense, bm25, 60, 10);

        assertThat(result).extracting(RetrievalHit::chunkId).containsExactly(2L, 1L, 4L, 3L);
        assertThat(result.get(0).score()).isCloseTo(1.0 / 62 + 1.0 / 61, offset(1.0e-12));
        assertThat(result.get(1).score()).isCloseTo(1.0 / 61 + 1.0 / 63, offset(1.0e-12));
        assertThat(result.get(2).score()).isCloseTo(1.0 / 62, offset(1.0e-12));
        assertThat(result.get(3).score()).isCloseTo(1.0 / 63, offset(1.0e-12));
        assertThat(result).allMatch(hit -> hit.scoreType() == RetrievalScoreType.RRF);
        assertThat(result.get(0).contributions()).containsExactly(
                new RetrievalContribution(RetrieverType.DENSE, 2, 0.82, RetrievalScoreType.COSINE),
                new RetrievalContribution(RetrieverType.BM25, 1, 8.73, RetrievalScoreType.BM25));
    }

    @Test
    void usesUnionAndDeduplicatesChunkAcrossRoutes() {
        List<RetrievalHit> result = fusion.fuse(
                List.of(hit(1L, 0.8, RetrievalScoreType.COSINE)),
                List.of(
                        hit(1L, 9.0, RetrievalScoreType.BM25),
                        hit(2L, 8.0, RetrievalScoreType.BM25)),
                60,
                10);

        assertThat(result).extracting(RetrievalHit::chunkId).containsExactly(1L, 2L);
        assertThat(result.get(0).score()).isCloseTo(2.0 / 61, offset(1.0e-12));
        assertThat(result.get(0).contributions()).hasSize(2);
    }

    @Test
    void handlesOneOrBothEmptyRoutes() {
        assertThat(fusion.fuse(
                List.of(),
                List.of(hit(2L, 8.0, RetrievalScoreType.BM25)),
                60,
                5)).extracting(RetrievalHit::chunkId).containsExactly(2L);
        assertThat(fusion.fuse(List.of(), List.of(), 60, 5)).isEmpty();
    }

    @Test
    void appliesDeterministicTieBreakByChunkId() {
        List<RetrievalHit> dense = List.of(
                hit(20L, 0.9, RetrievalScoreType.COSINE),
                hit(30L, 0.8, RetrievalScoreType.COSINE));
        List<RetrievalHit> bm25 = List.of(
                hit(10L, 100.0, RetrievalScoreType.BM25),
                hit(40L, 1.0, RetrievalScoreType.BM25));

        for (int iteration = 0; iteration < 20; iteration++) {
            assertThat(fusion.fuse(dense, bm25, 60, 10))
                    .extracting(RetrievalHit::chunkId)
                    .containsExactly(10L, 20L, 30L, 40L);
        }
    }

    @Test
    void limitsFinalTopKAfterUnion() {
        List<RetrievalHit> result = fusion.fuse(
                List.of(
                        hit(1L, 0.9, RetrievalScoreType.COSINE),
                        hit(2L, 0.8, RetrievalScoreType.COSINE),
                        hit(3L, 0.7, RetrievalScoreType.COSINE)),
                List.of(
                        hit(4L, 9.0, RetrievalScoreType.BM25),
                        hit(5L, 8.0, RetrievalScoreType.BM25),
                        hit(6L, 7.0, RetrievalScoreType.BM25)),
                60,
                2);

        assertThat(result).hasSize(2);
    }

    @Test
    void rawScoresNeverInfluenceFusionRanking() {
        List<RetrievalHit> result = fusion.fuse(
                List.of(
                        hit(1L, 0.51, RetrievalScoreType.COSINE),
                        hit(2L, 0.99, RetrievalScoreType.COSINE)),
                List.of(
                        hit(3L, 0.01, RetrievalScoreType.BM25),
                        hit(4L, 10_000.0, RetrievalScoreType.BM25)),
                60,
                10);

        assertThat(result).extracting(RetrievalHit::chunkId).containsExactly(1L, 3L, 2L, 4L);
    }

    private static RetrievalHit hit(long chunkId, double score, RetrievalScoreType scoreType) {
        return new RetrievalHit(
                chunkId, 10L, "fixture.txt", 0, score, scoreType,
                "content-" + chunkId, null, null);
    }
}
