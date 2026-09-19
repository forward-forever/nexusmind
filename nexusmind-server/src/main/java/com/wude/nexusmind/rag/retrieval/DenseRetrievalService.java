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
@ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "openai")
public class DenseRetrievalService implements RetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingBatchService embeddingService;
    private final DenseVectorIndex vectorIndex;
    private final MilvusCollectionNamingStrategy namingStrategy;
    private final RetrievalVisibilityFilter visibilityFilter;

    public DenseRetrievalService(KnowledgeBaseService knowledgeBaseService,
                                 EmbeddingBatchService embeddingService,
                                 DenseVectorIndex vectorIndex,
                                 MilvusCollectionNamingStrategy namingStrategy,
                                 RetrievalVisibilityFilter visibilityFilter) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.namingStrategy = namingStrategy;
        this.visibilityFilter = visibilityFilter;
    }

    @Override
    public RetrieverType type() {
        return RetrieverType.DENSE;
    }

    @Override
    public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query is required");
        }
        if (topK < 1 || topK > RetrievalLimits.MAX_ROUTE_CANDIDATES) {
            throw new IllegalArgumentException(
                    "topK must be between 1 and " + RetrievalLimits.MAX_ROUTE_CANDIDATES);
        }

        KnowledgeBase knowledgeBase = knowledgeBaseService.get(knowledgeBaseId);
        if (knowledgeBase.getStatus() != KnowledgeBaseStatus.ACTIVE) {
            throw new KnowledgeBaseInactiveException(knowledgeBaseId);
        }
        embeddingService.validateCompatibility(knowledgeBase);
        float[] queryVector = embeddingService.embedQuery(query, knowledgeBase.getEmbeddingDimension());
        String collectionName = namingStrategy.forKnowledgeBase(knowledgeBaseId);
        int candidateK = visibilityFilter.candidateLimit(topK);
        List<DenseVectorHit> candidates = vectorIndex.search(
                collectionName,
                knowledgeBaseId,
                queryVector,
                candidateK,
                knowledgeBase.getEmbeddingDimension());
        List<RetrievalHit> visibleHits = visibilityFilter.filter(
                knowledgeBaseId, candidates, topK, RetrievalScoreType.COSINE);
        return new RetrievalResult(
                query,
                knowledgeBaseId,
                embeddingService.model(),
                embeddingService.dimension(),
                RetrieverType.DENSE,
                RetrievalScoreType.COSINE,
                topK,
                visibleHits);
    }

}
