package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import com.wude.nexusmind.rag.milvus.DenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
public class DenseRetrievalService implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(DenseRetrievalService.class);
    public static final int DEFAULT_TOP_K = 5;
    public static final int MAXIMUM_TOP_K = 20;
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MAXIMUM_CANDIDATE_K = 60;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final EmbeddingBatchService embeddingService;
    private final DenseVectorIndex vectorIndex;
    private final MilvusCollectionNamingStrategy namingStrategy;

    public DenseRetrievalService(KnowledgeBaseService knowledgeBaseService,
                                 DocumentService documentService,
                                 EmbeddingBatchService embeddingService,
                                 DenseVectorIndex vectorIndex,
                                 MilvusCollectionNamingStrategy namingStrategy) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.namingStrategy = namingStrategy;
    }

    public DenseSearchResult search(long knowledgeBaseId, String query, int topK) {
        return DenseSearchResult.from(retrieve(knowledgeBaseId, query, topK));
    }

    @Override
    public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
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
        int candidateK = Math.min(topK * CANDIDATE_MULTIPLIER, MAXIMUM_CANDIDATE_K);
        List<DenseVectorHit> candidates = vectorIndex.search(
                collectionName,
                knowledgeBaseId,
                queryVector,
                candidateK,
                knowledgeBase.getEmbeddingDimension());
        List<RetrievalHit> visibleHits = filterVisibleHits(knowledgeBaseId, candidates, topK);
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

    private List<RetrievalHit> filterVisibleHits(long knowledgeBaseId,
                                                 List<DenseVectorHit> candidates,
                                                 int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> documentIds = candidates.stream()
                .map(DenseVectorHit::documentId)
                .distinct()
                .toList();
        Map<Long, KnowledgeDocument> documentsById = documentService.findByIds(documentIds).stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));

        List<RetrievalHit> visible = new ArrayList<>(Math.min(topK, candidates.size()));
        Set<Long> orphanDocumentIds = new LinkedHashSet<>();
        Set<Long> hiddenDocumentIds = new LinkedHashSet<>();
        for (DenseVectorHit candidate : candidates) {
            KnowledgeDocument document = documentsById.get(candidate.documentId());
            if (document == null) {
                orphanDocumentIds.add(candidate.documentId());
                continue;
            }
            if (!Long.valueOf(knowledgeBaseId).equals(document.getKnowledgeBaseId())
                    || document.getStatus() != DocumentStatus.READY
                    || document.getIndexStatus() != DocumentIndexStatus.INDEXED) {
                hiddenDocumentIds.add(document.getId());
                continue;
            }
            visible.add(new RetrievalHit(
                    candidate.chunkId(),
                    candidate.documentId(),
                    document.getOriginalFileName(),
                    candidate.chunkIndex(),
                    candidate.score(),
                    RetrievalScoreType.COSINE,
                    candidate.content(),
                    candidate.pageNo(),
                    candidate.sectionTitle()));
            if (visible.size() == topK) {
                break;
            }
        }

        if (!orphanDocumentIds.isEmpty()) {
            log.warn(
                    "Filtered stale Milvus candidates with orphan document IDs: knowledgeBaseId={}, documentIds={}",
                    knowledgeBaseId, orphanDocumentIds);
        }
        if (!hiddenDocumentIds.isEmpty()) {
            log.warn(
                    "Filtered Milvus candidates not visible from MySQL document state: knowledgeBaseId={}, documentIds={}",
                    knowledgeBaseId, hiddenDocumentIds);
        }
        return List.copyOf(visible);
    }
}
