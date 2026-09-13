package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
import com.wude.nexusmind.rag.milvus.Bm25SparseHit;
import com.wude.nexusmind.rag.milvus.Bm25SparseIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
public class Bm25RetrievalService implements RetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final Bm25SparseIndex sparseIndex;
    private final MilvusCollectionNamingStrategy namingStrategy;
    private final RetrievalVisibilityFilter visibilityFilter;

    public Bm25RetrievalService(KnowledgeBaseService knowledgeBaseService,
                                Bm25SparseIndex sparseIndex,
                                MilvusCollectionNamingStrategy namingStrategy,
                                RetrievalVisibilityFilter visibilityFilter) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.sparseIndex = sparseIndex;
        this.namingStrategy = namingStrategy;
        this.visibilityFilter = visibilityFilter;
    }

    @Override
    public RetrieverType type() {
        return RetrieverType.BM25;
    }

    @Override
    public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query is required");
        }
        if (topK < 1 || topK > DenseRetrievalService.MAXIMUM_TOP_K) {
            throw new IllegalArgumentException(
                    "topK must be between 1 and " + DenseRetrievalService.MAXIMUM_TOP_K);
        }

        KnowledgeBase knowledgeBase = knowledgeBaseService.get(knowledgeBaseId);
        if (knowledgeBase.getStatus() != KnowledgeBaseStatus.ACTIVE) {
            throw new KnowledgeBaseInactiveException(knowledgeBaseId);
        }
        String collectionName = namingStrategy.forKnowledgeBase(knowledgeBaseId);
        int candidateK = visibilityFilter.candidateLimit(topK);
        List<Bm25SparseHit> candidates = sparseIndex.search(
                collectionName,
                knowledgeBaseId,
                query,
                candidateK,
                knowledgeBase.getEmbeddingDimension());
        List<RetrievalHit> visibleHits = visibilityFilter.filter(
                knowledgeBaseId, candidates, topK, RetrievalScoreType.BM25);
        return new RetrievalResult(
                query,
                knowledgeBaseId,
                null,
                0,
                RetrieverType.BM25,
                RetrievalScoreType.BM25,
                topK,
                visibleHits);
    }
}
