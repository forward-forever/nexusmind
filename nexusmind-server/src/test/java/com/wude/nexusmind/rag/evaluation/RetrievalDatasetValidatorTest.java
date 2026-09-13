package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalDatasetValidatorTest {

    @Test
    void validatesAllExpectedChunksAndDocumentsWithBatchQueries() {
        Fixture fixture = fixture();
        when(fixture.chunks.findByIds(anyCollection())).thenReturn(List.of(
                chunk(101L, 12L, 10L), chunk(102L, 12L, 10L)));
        when(fixture.documents.findByIds(anyCollection())).thenReturn(List.of(
                document(10L, 12L, DocumentStatus.READY, DocumentIndexStatus.INDEXED)));

        fixture.validator.validate(dataset(12L, List.of(101L, 102L)));

        verify(fixture.chunks).findByIds(anyCollection());
        verify(fixture.documents).findByIds(anyCollection());
    }

    @Test
    void rejectsMissingOrWrongKnowledgeBaseChunks() {
        Fixture missing = fixture();
        when(missing.chunks.findByIds(anyCollection())).thenReturn(List.of());
        assertThatThrownBy(() -> missing.validator.validate(dataset(12L, List.of(101L))))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("missing knowledge chunks: [101]");

        Fixture wrongKnowledgeBase = fixture();
        when(wrongKnowledgeBase.chunks.findByIds(anyCollection()))
                .thenReturn(List.of(chunk(101L, 99L, 10L)));
        when(wrongKnowledgeBase.documents.findByIds(anyCollection())).thenReturn(List.of(
                document(10L, 99L, DocumentStatus.READY, DocumentIndexStatus.INDEXED)));
        assertThatThrownBy(() -> wrongKnowledgeBase.validator.validate(dataset(12L, List.of(101L))))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("does not belong to knowledge base 12");
    }

    @Test
    void rejectsGoldenChunksWhoseDocumentIsNotReadyAndIndexed() {
        Fixture fixture = fixture();
        when(fixture.chunks.findByIds(anyCollection())).thenReturn(List.of(chunk(101L, 12L, 10L)));
        when(fixture.documents.findByIds(anyCollection())).thenReturn(List.of(
                document(10L, 12L, DocumentStatus.READY, DocumentIndexStatus.FAILED)));

        assertThatThrownBy(() -> fixture.validator.validate(dataset(12L, List.of(101L))))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("not retrieval-visible");
    }

    private static Fixture fixture() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        ChunkService chunks = mock(ChunkService.class);
        DocumentService documents = mock(DocumentService.class);
        return new Fixture(
                new RetrievalDatasetValidator(knowledgeBases, chunks, documents),
                chunks,
                documents);
    }

    private static RetrievalEvaluationDataset dataset(long knowledgeBaseId, List<Long> relevantChunkIds) {
        return new RetrievalEvaluationDataset("test", List.of(
                new RetrievalEvaluationCase(
                        "q001", knowledgeBaseId, "question", relevantChunkIds, QueryCategory.EXACT, null)));
    }

    private static KnowledgeChunk chunk(long id, long knowledgeBaseId, long documentId) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                knowledgeBaseId, documentId, 0, "content", null, null, 7, null);
        chunk.setId(id);
        return chunk;
    }

    private static KnowledgeDocument document(long id,
                                               long knowledgeBaseId,
                                               DocumentStatus status,
                                               DocumentIndexStatus indexStatus) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setStatus(status);
        document.setIndexStatus(indexStatus);
        return document;
    }

    private record Fixture(
            RetrievalDatasetValidator validator,
            ChunkService chunks,
            DocumentService documents) {
    }
}
