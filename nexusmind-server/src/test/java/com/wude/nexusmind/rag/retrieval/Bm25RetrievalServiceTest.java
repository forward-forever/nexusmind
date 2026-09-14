package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
import com.wude.nexusmind.rag.milvus.Bm25SparseHit;
import com.wude.nexusmind.rag.milvus.Bm25SparseIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Bm25RetrievalServiceTest {

    @Test
    void searchesRawTextFiltersInvisibleCandidatesAndPreservesBm25Ranking() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        Bm25SparseIndex sparseIndex = mock(Bm25SparseIndex.class);
        when(knowledgeBases.get(7L)).thenReturn(knowledgeBase(KnowledgeBaseStatus.ACTIVE));
        List<Bm25SparseHit> candidates = List.of(
                hit(100L, 10L, 9.5f),
                hit(101L, 11L, 8.5f),
                hit(102L, 12L, 7.5f),
                hit(103L, 99L, 7.0f),
                hit(104L, 14L, 6.5f),
                hit(105L, 15L, 5.5f),
                hit(106L, 16L, 4.5f));
        when(sparseIndex.search("kb_7", 7L, "InnoDB 死锁", 15, 1024)).thenReturn(candidates);
        when(documents.findByIds(List.of(10L, 11L, 12L, 99L, 14L, 15L, 16L))).thenReturn(List.of(
                document(10L, 7L, DocumentStatus.READY, DocumentIndexStatus.FAILED),
                document(11L, 7L, DocumentStatus.READY, DocumentIndexStatus.INDEXING),
                document(12L, 7L, DocumentStatus.READY, DocumentIndexStatus.NOT_INDEXED),
                document(14L, 8L, DocumentStatus.READY, DocumentIndexStatus.INDEXED),
                document(15L, 7L, DocumentStatus.FAILED, DocumentIndexStatus.INDEXED),
                document(16L, 7L, DocumentStatus.READY, DocumentIndexStatus.INDEXED)));

        RetrievalResult result = service(knowledgeBases, documents, sparseIndex)
                .retrieve(7L, "InnoDB 死锁", 5);

        assertThat(result.retrieverType()).isEqualTo(RetrieverType.BM25);
        assertThat(result.scoreType()).isEqualTo(RetrievalScoreType.BM25);
        assertThat(result.model()).isNull();
        assertThat(result.dimension()).isZero();
        assertThat(result.hits()).extracting(RetrievalHit::chunkId).containsExactly(106L);
        assertThat(result.hits()).extracting(RetrievalHit::score).containsExactly(4.5);
        assertThat(result.hits()).allMatch(hit -> hit.scoreType() == RetrievalScoreType.BM25);
        verify(sparseIndex).search("kb_7", 7L, "InnoDB 死锁", 15, 1024);
        verify(documents).findByIds(List.of(10L, 11L, 12L, 99L, 14L, 15L, 16L));
    }

    @Test
    void hasNoEmbeddingDependencyAndRejectsInactiveKnowledgeBaseBeforeMilvus() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        Bm25SparseIndex sparseIndex = mock(Bm25SparseIndex.class);
        when(knowledgeBases.get(7L)).thenReturn(knowledgeBase(KnowledgeBaseStatus.DISABLED));

        assertThatThrownBy(() -> service(knowledgeBases, documents, sparseIndex)
                .retrieve(7L, "query", 5))
                .isInstanceOf(KnowledgeBaseInactiveException.class);

        verifyNoInteractions(documents, sparseIndex);
    }

    private static Bm25RetrievalService service(KnowledgeBaseService knowledgeBases,
                                                 DocumentService documents,
                                                 Bm25SparseIndex sparseIndex) {
        return new Bm25RetrievalService(
                knowledgeBases,
                sparseIndex,
                new MilvusCollectionNamingStrategy(),
                new RetrievalVisibilityFilter(documents));
    }

    private static Bm25SparseHit hit(long chunkId, long documentId, float score) {
        return new Bm25SparseHit(
                chunkId, documentId, 0, score, "content-" + chunkId, null, null);
    }

    private static KnowledgeDocument document(long id,
                                               long knowledgeBaseId,
                                               DocumentStatus status,
                                               DocumentIndexStatus indexStatus) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalFileName("document-" + id + ".pdf");
        document.setStatus(status);
        document.setIndexStatus(indexStatus);
        return document;
    }

    private static KnowledgeBase knowledgeBase(KnowledgeBaseStatus status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                "test", null, "qwen3.7-text-embedding-flash", 1024, status);
        knowledgeBase.setId(7L);
        return knowledgeBase;
    }
}
