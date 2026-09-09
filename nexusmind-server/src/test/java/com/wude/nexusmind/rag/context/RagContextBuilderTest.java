package com.wude.nexusmind.rag.context;

import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagContextBuilderTest {

    @Test
    void preservesRankingEnrichesAllMetadataAndFetchesDocumentsOnce() {
        DocumentService documents = mock(DocumentService.class);
        when(documents.findByIds(new LinkedHashSet<>(List.of(10L, 20L, 30L))))
                .thenReturn(List.of(
                        document(10L, "mysql.pdf"),
                        document(20L, "locks.md"),
                        document(30L, "notes.txt")));
        List<DenseVectorHit> hits = List.of(
                hit(101L, 10L, 0.92f, "PDF page content", 17, null),
                hit(201L, 20L, 0.81f, "Markdown content", null, "Deadlocks"),
                hit(301L, 30L, 0.72f, "TXT content", null, null),
                hit(102L, 10L, 0.65f, "Second PDF page", 18, null));

        RagContext context = builder(documents, 12_000).build(hits);

        assertThat(context.sources()).extracting(RagSource::sourceId)
                .containsExactly("S1", "S2", "S3", "S4");
        assertThat(context.sources()).extracting(RagSource::chunkId)
                .containsExactly(101L, 201L, 301L, 102L);
        assertThat(context.sources().get(0).fileName()).isEqualTo("mysql.pdf");
        assertThat(context.sources().get(0).pageNo()).isEqualTo(17);
        assertThat(context.sources().get(1).fileName()).isEqualTo("locks.md");
        assertThat(context.sources().get(1).sectionTitle()).isEqualTo("Deadlocks");
        assertThat(context.sources().get(2).fileName()).isEqualTo("notes.txt");
        assertThat(context.sources().get(2).pageNo()).isNull();
        assertThat(context.sources().get(2).sectionTitle()).isNull();
        assertThat(context.text()).contains("===== SOURCE S1 =====", "page: 17", "section: Deadlocks");
        assertThat(context.charCount()).isEqualTo(context.text().length());
        verify(documents).findByIds(new LinkedHashSet<>(List.of(10L, 20L, 30L)));
    }

    @Test
    void stopsBeforeAnEntireChunkThatWouldExceedTheCharacterGuard() {
        DocumentService documents = mock(DocumentService.class);
        when(documents.findByIds(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(document(10L, "first.txt"), document(20L, "second.txt")));
        DenseVectorHit first = hit(1L, 10L, 0.9f, "first-complete-content", null, null);
        DenseVectorHit second = hit(2L, 20L, 0.8f, "second-content-must-not-be-truncated", null, null);
        int firstBlockChars = builder(documents, 12_000).build(List.of(first)).charCount();

        RagContext guarded = builder(documents, firstBlockChars + 1).build(List.of(first, second));

        assertThat(guarded.sources()).hasSize(1);
        assertThat(guarded.sources().get(0).sourceId()).isEqualTo("S1");
        assertThat(guarded.text())
                .contains("first-complete-content")
                .doesNotContain("second-content-must-not-be-truncated", "SOURCE S2");
    }

    @Test
    void returnsNoSourcesRatherThanTruncatingTheFirstOversizedChunk() {
        DocumentService documents = mock(DocumentService.class);
        when(documents.findByIds(new LinkedHashSet<>(List.of(10L))))
                .thenReturn(List.of(document(10L, "large.txt")));

        RagContext context = builder(documents, 20)
                .build(List.of(hit(1L, 10L, 0.9f, "content-is-larger-than-the-budget", null, null)));

        assertThat(context.sources()).isEmpty();
        assertThat(context.text()).isEmpty();
        assertThat(context.charCount()).isZero();
    }

    private static RagContextBuilder builder(DocumentService documents, int maxContextChars) {
        return new RagContextBuilder(
                documents,
                new RagChatProperties(
                        "qwen3.5-flash", 0.2, 5, 10, maxContextChars, Duration.ofMinutes(2)));
    }

    private static KnowledgeDocument document(long id, String fileName) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setOriginalFileName(fileName);
        return document;
    }

    private static DenseVectorHit hit(long chunkId,
                                      long documentId,
                                      float score,
                                      String content,
                                      Integer pageNo,
                                      String sectionTitle) {
        return new DenseVectorHit(chunkId, documentId, 0, score, content, pageNo, sectionTitle);
    }
}
