package com.wude.nexusmind.rag.index;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentIndexStateService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.model.config.EmbeddingProperties;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.exception.EmbeddingGenerationException;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import com.wude.nexusmind.rag.milvus.DenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import com.wude.nexusmind.rag.milvus.VectorIndexEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexingServiceTest {

    @Test
    void groupsFortyOneChunksIntoTwentyTwentyOneAndMarksIndexed() {
        Fixture fixture = fixture(41, -1);

        KnowledgeDocument result = fixture.service.index(10L);

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(fixture.embeddingModel.batchSizes).containsExactly(20, 20, 1);
        assertThat(fixture.vectorIndex.upsertBatchSizes).containsExactly(20, 20, 1);
        assertThat(fixture.vectorIndex.ensureCalls).isEqualTo(1);
        verify(fixture.stateService).markIndexing(10L);
        verify(fixture.stateService).markIndexed(10L);
        verify(fixture.stateService, never()).markFailed(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void providerFailureAfterFirstBatchCompensatesMilvusAndMarksIndexFailed() {
        Fixture fixture = fixture(25, 2);

        assertThatThrownBy(() -> fixture.service.index(10L))
                .isInstanceOf(EmbeddingGenerationException.class)
                .hasMessageContaining("batch 2");

        assertThat(fixture.vectorIndex.upsertBatchSizes).containsExactly(20);
        assertThat(fixture.vectorIndex.deletedDocumentIds).containsExactly(10L);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(fixture.stateService).markFailed(org.mockito.ArgumentMatchers.eq(10L), error.capture());
        assertThat(error.getValue()).contains("batch 2").doesNotContain("chunk-");
        verify(fixture.stateService, never()).markIndexed(10L);
    }

    @Test
    void repeatedIndexExecutionUpsertsTheSameStableChunkIdentities() {
        Fixture fixture = fixture(3, -1);

        fixture.service.index(10L);
        fixture.service.index(10L);

        assertThat(fixture.vectorIndex.upsertChunkIds)
                .containsExactly(List.of(100L, 101L, 102L), List.of(100L, 101L, 102L));
    }

    private static Fixture fixture(int chunkCount, int failOnCall) {
        DocumentService documentService = mock(DocumentService.class);
        DocumentIndexStateService stateService = mock(DocumentIndexStateService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        ChunkService chunkService = mock(ChunkService.class);

        KnowledgeDocument document = new KnowledgeDocument(1L, "test.txt", "relative", "text/plain", 10L,
                "a".repeat(64));
        document.setId(10L);
        document.setStatus(DocumentStatus.READY);
        document.setIndexStatus(DocumentIndexStatus.NOT_INDEXED);
        when(documentService.get(10L)).thenReturn(document);

        KnowledgeBase knowledgeBase = new KnowledgeBase(
                "test", null, "qwen3.7-text-embedding-flash", 4, KnowledgeBaseStatus.ACTIVE);
        knowledgeBase.setId(1L);
        when(knowledgeBaseService.get(1L)).thenReturn(knowledgeBase);
        when(chunkService.listByDocument(10L)).thenReturn(chunks(chunkCount));

        CountingEmbeddingModel embeddingModel = new CountingEmbeddingModel(4, failOnCall);
        EmbeddingBatchService embeddingService = new EmbeddingBatchService(
                embeddingModel,
                new EmbeddingProperties("qwen3.7-text-embedding-flash", 4, 20, 7_500));
        RecordingVectorIndex vectorIndex = new RecordingVectorIndex();
        DocumentIndexingService service = new DocumentIndexingService(
                documentService,
                stateService,
                knowledgeBaseService,
                chunkService,
                embeddingService,
                new EmbeddingBatchPlanner(
                        new EmbeddingProperties("qwen3.7-text-embedding-flash", 4, 20, 7_500)),
                vectorIndex,
                new MilvusCollectionNamingStrategy());
        return new Fixture(service, stateService, embeddingModel, vectorIndex);
    }

    private static List<KnowledgeChunk> chunks(int count) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            KnowledgeChunk chunk = new KnowledgeChunk(
                    1L, 10L, index, "chunk-" + index, null, null, 7, null);
            chunk.setId(100L + index);
            chunks.add(chunk);
        }
        return chunks;
    }

    private record Fixture(DocumentIndexingService service,
                           DocumentIndexStateService stateService,
                           CountingEmbeddingModel embeddingModel,
                           RecordingVectorIndex vectorIndex) {
    }

    private static final class CountingEmbeddingModel implements EmbeddingModel {

        private final int dimension;
        private final int failOnCall;
        private final List<Integer> batchSizes = new ArrayList<>();

        private CountingEmbeddingModel(int dimension, int failOnCall) {
            this.dimension = dimension;
            this.failOnCall = failOnCall;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            batchSizes.add(texts.size());
            if (batchSizes.size() == failOnCall) {
                throw new IllegalStateException("provider unavailable");
            }
            return texts.stream().map(ignored -> new float[dimension]).toList();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingVectorIndex implements DenseVectorIndex {

        private final List<Integer> upsertBatchSizes = new ArrayList<>();
        private final List<Long> deletedDocumentIds = new ArrayList<>();
        private final List<List<Long>> upsertChunkIds = new ArrayList<>();
        private int ensureCalls;

        @Override
        public void ensureCollectionReady(String collectionName, int dimension) {
            ensureCalls++;
        }

        @Override
        public void ensureExistingCollectionReady(String collectionName, int dimension) {
        }

        @Override
        public void upsert(String collectionName, List<VectorIndexEntity> entities, int dimension) {
            upsertBatchSizes.add(entities.size());
            upsertChunkIds.add(entities.stream().map(entity -> entity.chunk().getId()).toList());
        }

        @Override
        public void deleteByDocumentId(String collectionName, long documentId) {
            deletedDocumentIds.add(documentId);
        }

        @Override
        public List<DenseVectorHit> search(String collectionName, long knowledgeBaseId,
                                           float[] queryVector, int topK, int dimension) {
            return List.of();
        }
    }
}
