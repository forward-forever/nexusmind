package com.wude.nexusmind.rag.index;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentIndexStateService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.milvus.MilvusCollectionAdmin;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeBaseIndexRebuildService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final DocumentIndexStateService indexStateService;
    private final DocumentIndexingService indexingService;
    private final EmbeddingBatchService embeddingService;
    private final MilvusCollectionAdmin collectionAdmin;
    private final MilvusCollectionNamingStrategy namingStrategy;

    public KnowledgeBaseIndexRebuildService(KnowledgeBaseService knowledgeBaseService,
                                            DocumentService documentService,
                                            DocumentIndexStateService indexStateService,
                                            DocumentIndexingService indexingService,
                                            EmbeddingBatchService embeddingService,
                                            MilvusCollectionAdmin collectionAdmin,
                                            MilvusCollectionNamingStrategy namingStrategy) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
        this.indexStateService = indexStateService;
        this.indexingService = indexingService;
        this.embeddingService = embeddingService;
        this.collectionAdmin = collectionAdmin;
        this.namingStrategy = namingStrategy;
    }

    public KnowledgeBaseIndexRebuildReport rebuild(long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.get(knowledgeBaseId);
        embeddingService.validateCompatibility(knowledgeBase);
        List<KnowledgeDocument> documents = documentService.listByKnowledgeBase(knowledgeBaseId).stream()
                .filter(document -> document.getStatus() == DocumentStatus.READY)
                .toList();

        int reset = indexStateService.resetReadyDocumentsForRebuild(knowledgeBaseId);
        if (reset != documents.size()) {
            throw new IllegalStateException(
                    "Rebuild status reset count %d does not match READY document count %d"
                            .formatted(reset, documents.size()));
        }

        String collectionName = namingStrategy.forKnowledgeBase(knowledgeBaseId);
        collectionAdmin.dropCollection(collectionName);
        collectionAdmin.ensureCollectionReady(collectionName, knowledgeBase.getEmbeddingDimension());

        int indexed = 0;
        List<KnowledgeBaseIndexRebuildReport.Failure> failures = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            try {
                indexingService.index(document.getId());
                indexed++;
            } catch (RuntimeException exception) {
                failures.add(new KnowledgeBaseIndexRebuildReport.Failure(
                        document.getId(), safeMessage(exception)));
            }
        }
        return new KnowledgeBaseIndexRebuildReport(
                knowledgeBaseId, documents.size(), indexed, failures);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
