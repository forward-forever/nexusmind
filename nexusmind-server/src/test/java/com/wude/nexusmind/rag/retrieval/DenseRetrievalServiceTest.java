package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
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
    void embedsQueryAndReturnsRawCosineResultsWithDebugMetadata() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        DenseVectorIndex index = mock(DenseVectorIndex.class);
        float[] queryVector = new float[]{1f, 0f, 0f, 0f};
        when(knowledgeBaseService.get(7L)).thenReturn(knowledgeBase(KnowledgeBaseStatus.ACTIVE));
        when(model.embed(List.of("InnoDB deadlock"))).thenReturn(List.of(queryVector));
        List<DenseVectorHit> hits = List.of(
                new DenseVectorHit(100L, 10L, 4, 0.82f, "deadlock content", 12, "Deadlocks"));
        when(index.search("kb_7", 7L, queryVector, 5, 4)).thenReturn(hits);

        DenseSearchResult result = service(knowledgeBaseService, model, index)
                .search(7L, "InnoDB deadlock", 5);

        assertThat(result.query()).isEqualTo("InnoDB deadlock");
        assertThat(result.knowledgeBaseId()).isEqualTo(7L);
        assertThat(result.model()).isEqualTo("qwen3.7-text-embedding-flash");
        assertThat(result.dimension()).isEqualTo(4);
        assertThat(result.metric()).isEqualTo("COSINE");
        assertThat(result.topK()).isEqualTo(5);
        assertThat(result.results()).containsExactlyElementsOf(hits);
        verify(index).search("kb_7", 7L, queryVector, 5, 4);
    }

    @Test
    void rejectsInactiveKnowledgeBaseBeforeEmbeddingOrMilvus() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        DenseVectorIndex index = mock(DenseVectorIndex.class);
        when(knowledgeBaseService.get(7L)).thenReturn(knowledgeBase(KnowledgeBaseStatus.DISABLED));

        assertThatThrownBy(() -> service(knowledgeBaseService, model, index)
                .search(7L, "query", 5))
                .isInstanceOf(KnowledgeBaseInactiveException.class);

        verifyNoInteractions(model, index);
    }

    @Test
    void rejectsTopKOutsideOneToTwenty() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        DenseVectorIndex index = mock(DenseVectorIndex.class);
        DenseRetrievalService service = service(knowledgeBaseService, model, index);

        assertThatThrownBy(() -> service.search(7L, "query", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 20");
        assertThatThrownBy(() -> service.search(7L, "query", 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 20");
        verifyNoInteractions(knowledgeBaseService, model, index);
    }

    private static DenseRetrievalService service(KnowledgeBaseService knowledgeBaseService,
                                                  EmbeddingModel model,
                                                  DenseVectorIndex index) {
        return new DenseRetrievalService(
                knowledgeBaseService,
                new EmbeddingBatchService(
                        model,
                        new EmbeddingProperties("qwen3.7-text-embedding-flash", 4, 20)),
                index,
                new MilvusCollectionNamingStrategy());
    }

    private static KnowledgeBase knowledgeBase(KnowledgeBaseStatus status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                "test", null, "qwen3.7-text-embedding-flash", 4, status);
        knowledgeBase.setId(7L);
        return knowledgeBase;
    }
}
