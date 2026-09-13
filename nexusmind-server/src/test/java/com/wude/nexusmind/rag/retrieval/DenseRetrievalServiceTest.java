package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.model.config.EmbeddingProperties;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import com.wude.nexusmind.rag.milvus.DenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DenseRetrievalServiceTest {

    @Test
    void filtersStaleCandidatesInOneMySqlLookupAndPreservesVisibleRanking() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        DenseVectorIndex index = mock(DenseVectorIndex.class);
        float[] queryVector = new float[]{1f, 0f, 0f, 0f};
        when(knowledgeBases.get(7L)).thenReturn(knowledgeBase(KnowledgeBaseStatus.ACTIVE));
        when(model.embed(List.of("InnoDB deadlock"))).thenReturn(List.of(queryVector));
        List<DenseVectorHit> candidates = List.of(
                hit(100L, 10L, 0.99f), hit(101L, 11L, 0.95f),
                hit(102L, 12L, 0.90f), hit(103L, 99L, 0.88f),
                hit(107L, 16L, 0.85f),
                hit(104L, 13L, 0.82f), hit(105L, 14L, 0.79f),
                hit(106L, 15L, 0.75f));
        when(index.search("kb_7", 7L, queryVector, 15, 4)).thenReturn(candidates);
        when(documents.findByIds(List.of(10L, 11L, 12L, 99L, 16L, 13L, 14L, 15L))).thenReturn(List.of(
                document(10L, 7L, DocumentStatus.READY, DocumentIndexStatus.FAILED),
                document(11L, 7L, DocumentStatus.READY, DocumentIndexStatus.INDEXING),
                document(12L, 7L, DocumentStatus.READY, DocumentIndexStatus.NOT_INDEXED),
                document(16L, 7L, DocumentStatus.FAILED, DocumentIndexStatus.INDEXED),
                document(13L, 7L, DocumentStatus.READY, DocumentIndexStatus.INDEXED),
                document(14L, 8L, DocumentStatus.READY, DocumentIndexStatus.INDEXED),
                document(15L, 7L, DocumentStatus.READY, DocumentIndexStatus.INDEXED)));

        RetrievalResult result = service(knowledgeBases, documents, model, index)
                .retrieve(7L, "InnoDB deadlock", 5);

        assertThat(result.retrieverType()).isEqualTo(RetrieverType.DENSE);
        assertThat(result.scoreType()).isEqualTo(RetrievalScoreType.COSINE);
        assertThat(result.hits()).extracting(RetrievalHit::chunkId).containsExactly(104L, 106L);
        assertThat(result.hits()).extracting(RetrievalHit::score).containsExactly(0.82f, 0.75f);
        assertThat(result.hits()).extracting(RetrievalHit::fileName)
                .containsExactly("document-13.pdf", "document-15.pdf");
        assertThat(result.hits()).allMatch(hit -> hit.scoreType() == RetrievalScoreType.COSINE);
        verify(documents).findByIds(List.of(10L, 11L, 12L, 99L, 16L, 13L, 14L, 15L));
        verify(index).search("kb_7", 7L, queryVector, 15, 4);
    }

    @Test
    void denseDebugResponseKeepsExistingShapeAndAddsTypedScores() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        DenseVectorIndex index = mock(DenseVectorIndex.class);
        float[] queryVector = new float[]{1f, 0f, 0f, 0f};
        when(knowledgeBases.get(7L)).thenReturn(knowledgeBase(KnowledgeBaseStatus.ACTIVE));
        when(model.embed(List.of("query"))).thenReturn(List.of(queryVector));
        when(index.search("kb_7", 7L, queryVector, 15, 4)).thenReturn(List.of(hit(100L, 10L, 0.82f)));
        when(documents.findByIds(List.of(10L))).thenReturn(List.of(
                document(10L, 7L, DocumentStatus.READY, DocumentIndexStatus.INDEXED)));

        DenseSearchResult result = service(knowledgeBases, documents, model, index).search(7L, "query", 5);

        assertThat(result.metric()).isEqualTo("COSINE");
        assertThat(result.topK()).isEqualTo(5);
        assertThat(result.results()).singleElement()
                .satisfies(hit -> assertThat(hit.scoreType()).isEqualTo(RetrievalScoreType.COSINE));
    }

    @Test
    void rejectsInactiveKnowledgeBaseBeforeEmbeddingMilvusOrDocumentLookup() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentService documents = mock(DocumentService.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        DenseVectorIndex index = mock(DenseVectorIndex.class);
        when(knowledgeBases.get(7L)).thenReturn(knowledgeBase(KnowledgeBaseStatus.DISABLED));

        assertThatThrownBy(() -> service(knowledgeBases, documents, model, index)
                .retrieve(7L, "query", 5))
                .isInstanceOf(KnowledgeBaseInactiveException.class);

        verifyNoInteractions(documents, model, index);
    }

    private static DenseRetrievalService service(KnowledgeBaseService knowledgeBases,
                                                  DocumentService documents,
                                                  EmbeddingModel model,
                                                  DenseVectorIndex index) {
        return new DenseRetrievalService(
                knowledgeBases,
                documents,
                new EmbeddingBatchService(
                        model,
                        new EmbeddingProperties("qwen3.7-text-embedding-flash", 4, 20, 7_500)),
                index,
                new MilvusCollectionNamingStrategy());
    }

    private static DenseVectorHit hit(long chunkId, long documentId, float score) {
        return new DenseVectorHit(chunkId, documentId, 0, score, "content-" + chunkId, 12, "section");
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
                "test", null, "qwen3.7-text-embedding-flash", 4, status);
        knowledgeBase.setId(7L);
        return knowledgeBase;
    }
}
