package com.wude.nexusmind.rag.context;

import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
    void stopsBeforeAnEntireChunkThatWouldExceedTheCharacterGuard() {
        RetrievalHit first = hit(1L, 10L, "first.txt", 0.9f,
                "first-complete-content", null, null);
        RetrievalHit second = hit(2L, 20L, "second.txt", 0.8f,
                "second-content-must-not-be-truncated", null, null);
        int firstBlockChars = builder(12_000).build(List.of(first)).charCount();

        RagContext guarded = builder(firstBlockChars + 1).build(List.of(first, second));

        assertThat(guarded.sources()).hasSize(1);
        assertThat(guarded.sources().get(0).sourceId()).isEqualTo("S1");
        assertThat(guarded.text())
                .contains("first-complete-content")
                .doesNotContain("second-content-must-not-be-truncated", "SOURCE S2");
    }

    @Test
    void returnsNoSourcesRatherThanTruncatingTheFirstOversizedChunk() {
        RagContext context = builder(20).build(List.of(
                hit(1L, 10L, "large.txt", 0.9f,
                        "content-is-larger-than-the-budget", null, null)));

        assertThat(context.sources()).isEmpty();
        assertThat(context.text()).isEmpty();
        assertThat(context.charCount()).isZero();
    }

    private static RagContextBuilder builder(int maxContextChars) {
        return new RagContextBuilder(new RagChatProperties(
                "qwen3.5-flash", 0.2, 5, 10, maxContextChars,
                Duration.ofMinutes(2), Duration.ofSeconds(150)));
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
