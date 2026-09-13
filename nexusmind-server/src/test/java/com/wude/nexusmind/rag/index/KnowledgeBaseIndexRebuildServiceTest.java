package com.wude.nexusmind.rag.index;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentIndexStateService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.milvus.MilvusCollectionAdmin;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseIndexRebuildServiceTest {

    @Test
    void resetsReadyDocumentsDropsAndRecreatesCollectionThenIndexesEveryDocument() {
        Fixture fixture = fixture();

        KnowledgeBaseIndexRebuildReport report = fixture.service.rebuild(7L);

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.indexed()).isEqualTo(2);
        assertThat(report.failed()).isZero();
        InOrder order = inOrder(fixture.stateService, fixture.collectionAdmin, fixture.indexingService);
        order.verify(fixture.stateService).resetReadyDocumentsForRebuild(7L);
        order.verify(fixture.collectionAdmin).dropCollection("kb_7");
        order.verify(fixture.collectionAdmin).ensureCollectionReady("kb_7", 1024);
        order.verify(fixture.indexingService).index(10L);
        order.verify(fixture.indexingService).index(11L);
    }

    @Test
    void keepsSuccessfulDocumentsAndReportsIndividualFailure() {
        Fixture fixture = fixture();
        when(fixture.indexingService.index(10L)).thenThrow(new IllegalStateException("provider unavailable"));

        KnowledgeBaseIndexRebuildReport report = fixture.service.rebuild(7L);

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.indexed()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.documentId()).isEqualTo(10L);
            assertThat(failure.message()).contains("provider unavailable");
        });
        verify(fixture.indexingService).index(11L);
    }

    @Test
    void refusesToDropWhenStatusResetCountIsInconsistent() {
        Fixture fixture = fixture();
        when(fixture.stateService.resetReadyDocumentsForRebuild(7L)).thenReturn(1);

        assertThatThrownBy(() -> fixture.service.rebuild(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reset count");
    }

    private static Fixture fixture() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        DocumentIndexStateService stateService = mock(DocumentIndexStateService.class);
        DocumentIndexingService indexingService = mock(DocumentIndexingService.class);
        EmbeddingBatchService embeddingService = mock(EmbeddingBatchService.class);
        MilvusCollectionAdmin collectionAdmin = mock(MilvusCollectionAdmin.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                "test", null, "qwen3.7-text-embedding-flash", 1024, KnowledgeBaseStatus.ACTIVE);
        knowledgeBase.setId(7L);
        when(knowledgeBases.get(7L)).thenReturn(knowledgeBase);
        when(documents.listByKnowledgeBase(7L)).thenReturn(List.of(
                document(10L, DocumentStatus.READY),
                document(11L, DocumentStatus.READY),
                document(12L, DocumentStatus.FAILED)));
        when(stateService.resetReadyDocumentsForRebuild(7L)).thenReturn(2);

        KnowledgeBaseIndexRebuildService service = new KnowledgeBaseIndexRebuildService(
                knowledgeBases,
                documents,
                stateService,
                indexingService,
                embeddingService,
                collectionAdmin,
                new MilvusCollectionNamingStrategy());
        return new Fixture(service, stateService, indexingService, collectionAdmin);
    }

    private static KnowledgeDocument document(long id, DocumentStatus status) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setKnowledgeBaseId(7L);
        document.setStatus(status);
        return document;
    }

    private record Fixture(KnowledgeBaseIndexRebuildService service,
                           DocumentIndexStateService stateService,
                           DocumentIndexingService indexingService,
                           MilvusCollectionAdmin collectionAdmin) {
    }
}
