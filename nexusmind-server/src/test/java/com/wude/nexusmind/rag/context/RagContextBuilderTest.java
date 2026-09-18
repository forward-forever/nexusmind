package com.wude.nexusmind.rag.context;

import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.support.TestTokenSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagContextBuilderTest {

    @Test
    void preservesRankingAndPropagatesEnrichedMetadataAndScoreType() {
        List<RetrievalHit> hits = List.of(
                hit(101L, 10L, "mysql.pdf", 0.92f, "PDF page content", 17, null),
                hit(201L, 20L, "locks.md", 0.81f, "Markdown content", null, "Deadlocks"),
                hit(301L, 30L, "notes.txt", 0.72f, "TXT content", null, null),
                hit(102L, 10L, "mysql.pdf", 0.65f, "Second PDF page", 18, null));

        RagContext context = builder(12_000).build(hits);

        assertThat(context.sources()).extracting(RagSource::sourceId)
                .containsExactly("S1", "S2", "S3", "S4");
        assertThat(context.sources()).extracting(RagSource::chunkId)
                .containsExactly(101L, 201L, 301L, 102L);
        assertThat(context.sources().get(0).fileName()).isEqualTo("mysql.pdf");
        assertThat(context.sources().get(0).pageNo()).isEqualTo(17);
        assertThat(context.sources().get(1).sectionTitle()).isEqualTo("Deadlocks");
        assertThat(context.sources().get(2).pageNo()).isNull();
        assertThat(context.sources().get(2).sectionTitle()).isNull();
        assertThat(context.sources()).allMatch(source -> source.scoreType() == RetrievalScoreType.COSINE);
        assertThat(context.text()).contains(
                "===== SOURCE S1 =====", "page: 17", "section: Deadlocks", "score: COSINE 0.920000");
        assertThat(context.charCount()).isEqualTo(context.text().length());
    }

    @Test
    void stopsAtTheRankPrefixWhenTheNextFormattedSourceExceedsTheTokenBudget() {
        RetrievalHit first = hit(1L, 10L, "first.txt", 0.9f,
                "first-complete-content", null, null);
        RetrievalHit second = hit(2L, 20L, "second.txt", 0.8f,
                "second-content-must-not-be-truncated", null, null);
        int firstBlockTokens = builder(12_000).build(List.of(first)).estimatedTokens();

        RagContext guarded = builder(12_000).build(List.of(first, second), firstBlockTokens + 1);

        assertThat(guarded.sources()).hasSize(1);
        assertThat(guarded.sources().get(0).sourceId()).isEqualTo("S1");
        assertThat(guarded.text())
                .contains("first-complete-content")
                .doesNotContain("second-content-must-not-be-truncated", "SOURCE S2");
    }

    @Test
    void truncatesTheFirstOversizedChunkWhilePreservingItsSourceMetadata() {
        RetrievalHit hit = hit(1L, 10L, "large.txt", 0.9f,
                "x".repeat(500), null, null);
        int metadataOnly = builder(12_000).build(List.of(
                hit(1L, 10L, "large.txt", 0.9f, "", null, null))).estimatedTokens();
        RagContext context = builder(12_000).build(List.of(hit), metadataOnly + 40);

        assertThat(context.sources()).singleElement().satisfies(source -> {
            assertThat(source.sourceId()).isEqualTo("S1");
            assertThat(source.fileName()).isEqualTo("large.txt");
            assertThat(source.content()).endsWith("… [truncated]");
        });
        assertThat(context.estimatedTokens()).isLessThanOrEqualTo(metadataOnly + 40);
        assertThat(context.truncated()).isTrue();
    }

    @Test
    void estimatedTokensIncludeFormattedSourceMetadataNotOnlyContent() {
        RagContext shortMetadata = builder(12_000).build(List.of(
                hit(1L, 10L, "a", 0.9f, "same", null, null)));
        RagContext longMetadata = builder(12_000).build(List.of(
                hit(1L, 10L, "a-very-long-file-name-for-token-accounting.pdf", 0.9f,
                        "same", 99, "a long section title")));

        assertThat(longMetadata.estimatedTokens()).isGreaterThan(shortMetadata.estimatedTokens());
    }

    private static RagContextBuilder builder(int maxTokens) {
        return new RagContextBuilder(
                new RagContextProperties(maxTokens),
                TestTokenSupport.estimator(),
                TestTokenSupport.truncator());
    }

    private static RetrievalHit hit(long chunkId,
                                    long documentId,
                                    String fileName,
                                    float score,
                                    String content,
                                    Integer pageNo,
                                    String sectionTitle) {
        return new RetrievalHit(
                chunkId, documentId, fileName, 0, score, RetrievalScoreType.COSINE,
                content, pageNo, sectionTitle);
    }
}
