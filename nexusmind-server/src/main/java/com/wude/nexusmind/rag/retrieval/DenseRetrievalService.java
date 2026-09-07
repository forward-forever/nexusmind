package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import com.wude.nexusmind.rag.milvus.DenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
public class DenseRetrievalService {

    public static final int DEFAULT_TOP_K = 5;
    public static final int MAXIMUM_TOP_K = 20;

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingBatchService embeddingService;
    private final DenseVectorIndex vectorIndex;
    private final MilvusCollectionNamingStrategy namingStrategy;

    public DenseRetrievalService(KnowledgeBaseService knowledgeBaseService,
                                 EmbeddingBatchService embeddingService,
                                 DenseVectorIndex vectorIndex,
                                 MilvusCollectionNamingStrategy namingStrategy) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.namingStrategy = namingStrategy;
    }

    public DenseSearchResult search(long knowledgeBaseId, String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query is required");
        }
        if (topK < 1 || topK > MAXIMUM_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and " + MAXIMUM_TOP_K);
        }

        KnowledgeBase knowledgeBase = knowledgeBaseService.get(knowledgeBaseId);
        if (knowledgeBase.getStatus() != KnowledgeBaseStatus.ACTIVE) {
            throw new KnowledgeBaseInactiveException(knowledgeBaseId);
        }
        embeddingService.validateCompatibility(knowledgeBase);
        float[] queryVector = embeddingService.embedQuery(query, knowledgeBase.getEmbeddingDimension());
        String collectionName = namingStrategy.forKnowledgeBase(knowledgeBaseId);
        List<DenseVectorHit> results = vectorIndex.search(
                collectionName,
                knowledgeBaseId,
                queryVector,
                topK,
                knowledgeBase.getEmbeddingDimension());
        return new DenseSearchResult(
                query,
                knowledgeBaseId,
                embeddingService.model(),
                embeddingService.dimension(),
                "COSINE",
                topK,
                results);
    }
}
