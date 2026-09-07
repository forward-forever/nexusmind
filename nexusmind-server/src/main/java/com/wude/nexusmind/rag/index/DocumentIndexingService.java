package com.wude.nexusmind.rag.index;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentIndexStateService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.exception.VectorIndexException;
import com.wude.nexusmind.rag.milvus.DenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import com.wude.nexusmind.rag.milvus.VectorIndexEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
public class DocumentIndexingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexingService.class);

    private final DocumentService documentService;
    private final DocumentIndexStateService indexStateService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkService chunkService;
    private final EmbeddingBatchService embeddingService;
    private final DenseVectorIndex vectorIndex;
    private final MilvusCollectionNamingStrategy namingStrategy;

    public DocumentIndexingService(DocumentService documentService,
                                   DocumentIndexStateService indexStateService,
                                   KnowledgeBaseService knowledgeBaseService,
                                   ChunkService chunkService,
                                   EmbeddingBatchService embeddingService,
                                   DenseVectorIndex vectorIndex,
                                   MilvusCollectionNamingStrategy namingStrategy) {
        this.documentService = documentService;
        this.indexStateService = indexStateService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.namingStrategy = namingStrategy;
    }

    public KnowledgeDocument index(long documentId) {
        indexStateService.markIndexing(documentId);
        String collectionName = null;
        boolean milvusWriteAttempted = false;
        try {
            KnowledgeDocument document = documentService.get(documentId);
            collectionName = namingStrategy.forKnowledgeBase(document.getKnowledgeBaseId());
            KnowledgeBase knowledgeBase = knowledgeBaseService.get(document.getKnowledgeBaseId());
            embeddingService.validateCompatibility(knowledgeBase);
            List<KnowledgeChunk> chunks = chunkService.listByDocument(documentId);
            if (chunks.isEmpty()) {
                throw new VectorIndexException("READY document has no chunks to index: " + documentId);
            }

            boolean collectionReady = false;
            int batchNumber = 0;
            for (int start = 0; start < chunks.size(); start += embeddingService.batchSize()) {
                batchNumber++;
                int end = Math.min(start + embeddingService.batchSize(), chunks.size());
                List<KnowledgeChunk> batch = chunks.subList(start, end);
                List<float[]> vectors = embeddingService.embedBatch(
                        batch.stream().map(KnowledgeChunk::getContent).toList(),
                        knowledgeBase.getEmbeddingDimension(),
                        batchNumber);

                if (!collectionReady) {
                    vectorIndex.ensureCollectionReady(collectionName, knowledgeBase.getEmbeddingDimension());
                    collectionReady = true;
                }

                List<VectorIndexEntity> entities = new ArrayList<>(batch.size());
                for (int index = 0; index < batch.size(); index++) {
                    entities.add(new VectorIndexEntity(batch.get(index), vectors.get(index)));
                }
                milvusWriteAttempted = true;
                vectorIndex.upsert(collectionName, entities, knowledgeBase.getEmbeddingDimension());
            }

            indexStateService.markIndexed(documentId);
            return documentService.get(documentId);
        } catch (RuntimeException indexingFailure) {
            RuntimeException compensationFailure = null;
            if (milvusWriteAttempted && collectionName != null) {
                try {
                    vectorIndex.deleteByDocumentId(collectionName, documentId);
                } catch (RuntimeException exception) {
                    compensationFailure = exception;
                    log.error("Milvus compensation failed: documentId={}, collection={}",
                            documentId, collectionName, exception);
                }
            }
            markFailed(documentId, indexingFailure, compensationFailure);
            throw indexingFailure;
        }
    }

    private void markFailed(long documentId,
                            RuntimeException indexingFailure,
                            RuntimeException compensationFailure) {
        String message = safeFailureMessage(indexingFailure);
        if (compensationFailure != null) {
            message += "; Milvus compensation deletion also failed";
        }
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        try {
            indexStateService.markFailed(documentId, message);
        } catch (RuntimeException statusFailure) {
            indexingFailure.addSuppressed(statusFailure);
            log.error("Failed to mark document index status as FAILED: documentId={}", documentId, statusFailure);
        }
    }

    private static String safeFailureMessage(RuntimeException failure) {
        if (failure instanceof VectorIndexException
                || failure instanceof com.wude.nexusmind.rag.exception.EmbeddingGenerationException
                || failure instanceof com.wude.nexusmind.rag.exception.EmbeddingConfigurationMismatchException) {
            String message = failure.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return "Dense vector indexing failed";
    }
}
