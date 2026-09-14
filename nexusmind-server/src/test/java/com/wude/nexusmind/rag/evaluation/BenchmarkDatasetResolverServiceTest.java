package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.service.DocumentService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BenchmarkDatasetResolverServiceTest {

    @Test
    void resolvesSinglePageToSingleChunk() {
        Fixture fixture = fixture(List.of(chunk(101, 0, 1)));

        BenchmarkDatasetResolution result = fixture.service.resolve(25, List.of(source("q001", 1)));

        assertThat(result.knowledgeBaseId()).isEqualTo(12);
        assertThat(result.cases().get(0).relevantChunkIds()).containsExactly(101L);
        assertThat(result.referencedPageCount()).isEqualTo(1);
        assertThat(result.resolvedChunkCount()).isEqualTo(1);
        verify(fixture.documentService, times(1)).get(25);
        verify(fixture.chunkMapper, times(1)).findByDocumentId(25);
    }

    @Test
    void resolvesSinglePageToMultipleChunksInChunkIndexOrder() {
        Fixture fixture = fixture(List.of(chunk(102, 2, 4), chunk(101, 1, 4)));

        BenchmarkDatasetResolution result = fixture.service.resolve(25, List.of(source("q001", 4)));

        assertThat(result.cases().get(0).relevantChunkIds()).containsExactly(101L, 102L);
    }

    @Test
    void resolvesMultiplePagesInExpectedPageThenChunkOrder() {
        Fixture fixture = fixture(List.of(
                chunk(401, 4, 4), chunk(301, 3, 3), chunk(402, 5, 4)));
        BenchmarkSourceCase source = new BenchmarkSourceCase(
                "q001", "question", QueryCategory.SEMANTIC, List.of(4, 3), null, null);

        BenchmarkDatasetResolution result = fixture.service.resolve(25, List.of(source));

        assertThat(result.cases().get(0).relevantChunkIds()).containsExactly(401L, 402L, 301L);
        assertThat(result.mappings().get(0).expectedPages()).containsExactly(4, 3);
    }

    @Test
    void deduplicatesExpectedPagesWhenCalledDirectly() {
        Fixture fixture = fixture(List.of(chunk(101, 0, 1)));
        BenchmarkSourceCase source = new BenchmarkSourceCase(
                "q001", "question", QueryCategory.EXACT, List.of(1, 1), null, null);

        BenchmarkDatasetResolution result = fixture.service.resolve(25, List.of(source));

        assertThat(result.cases().get(0).relevantChunkIds()).containsExactly(101L);
        assertThat(result.mappings().get(0).expectedPages()).containsExactly(1);
    }

    @Test
    void missingPageFailsWholeResolutionAndReportsQueryPageAndConcept() {
        Fixture fixture = fixture(List.of(chunk(101, 0, 1)));
        BenchmarkSourceCase source = new BenchmarkSourceCase(
                "q009", "question", QueryCategory.EXACT, List.of(9), "Deadlock", null);

        assertThatThrownBy(() -> fixture.service.resolve(25, List.of(source)))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("q009")
                .hasMessageContaining("page 9")
                .hasMessageContaining("Deadlock");
    }

    @Test
    void rejectsDocumentThatIsNotReady() {
        KnowledgeDocument document = document(DocumentStatus.FAILED, DocumentIndexStatus.INDEXED);
        Fixture fixture = fixture(document, List.of(chunk(101, 0, 1)));

        assertThatThrownBy(() -> fixture.service.resolve(25, List.of(source("q001", 1))))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("must be READY")
                .hasMessageContaining("FAILED");
    }

    @Test
    void rejectsDocumentThatIsNotIndexed() {
        KnowledgeDocument document = document(DocumentStatus.READY, DocumentIndexStatus.NOT_INDEXED);
        Fixture fixture = fixture(document, List.of(chunk(101, 0, 1)));

        assertThatThrownBy(() -> fixture.service.resolve(25, List.of(source("q001", 1))))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("must be INDEXED")
                .hasMessageContaining("NOT_INDEXED");
    }

    @Test
    void rejectsDocumentWithoutChunks() {
        Fixture fixture = fixture(List.of());

        assertThatThrownBy(() -> fixture.service.resolve(25, List.of(source("q001", 1))))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("has no chunks");
    }

    @Test
    void rejectsChunkFromAnotherDocumentOrKnowledgeBase() {
        KnowledgeChunk foreign = chunk(101, 0, 1);
        foreign.setDocumentId(99L);
        Fixture fixture = fixture(List.of(foreign));

        assertThatThrownBy(() -> fixture.service.resolve(25, List.of(source("q001", 1))))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("does not belong to document 25");
    }

    private static Fixture fixture(List<KnowledgeChunk> chunks) {
        return fixture(document(DocumentStatus.READY, DocumentIndexStatus.INDEXED), chunks);
    }

    private static Fixture fixture(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        when(documentService.get(25)).thenReturn(document);
        when(chunkMapper.findByDocumentId(25)).thenReturn(chunks);
        return new Fixture(
                new BenchmarkDatasetResolverService(documentService, chunkMapper),
                documentService,
                chunkMapper);
    }

    private static KnowledgeDocument document(DocumentStatus status, DocumentIndexStatus indexStatus) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(25L);
        document.setKnowledgeBaseId(12L);
        document.setStatus(status);
        document.setIndexStatus(indexStatus);
        return document;
    }

    private static KnowledgeChunk chunk(long id, int chunkIndex, int pageNo) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                12L, 25L, chunkIndex, "content", pageNo, null, 7, null);
        chunk.setId(id);
        return chunk;
    }

    private static BenchmarkSourceCase source(String id, int page) {
        return new BenchmarkSourceCase(
                id, "question", QueryCategory.EXACT, List.of(page), null, "manual");
    }

    private record Fixture(
            BenchmarkDatasetResolverService service,
            DocumentService documentService,
            KnowledgeChunkMapper chunkMapper) {
    }
}
