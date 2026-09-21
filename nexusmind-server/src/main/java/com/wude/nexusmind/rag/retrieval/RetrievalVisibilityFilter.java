package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.rag.milvus.RetrievalCandidateHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *  检查检索结果的可见性，过滤掉无效的检索结果，如孤儿文档、未索引的文档、状态不为READY的文档
 */
public class RetrievalVisibilityFilter {

    private static final Logger log = LoggerFactory.getLogger(RetrievalVisibilityFilter.class);
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MAXIMUM_CANDIDATE_K = 60;

    private final DocumentService documentService;

    public RetrievalVisibilityFilter(DocumentService documentService) {
        this.documentService = documentService;
    }

    public int candidateLimit(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        return Math.min(topK * CANDIDATE_MULTIPLIER, MAXIMUM_CANDIDATE_K);
    }

    public List<RetrievalHit> filter(long knowledgeBaseId,
                                     List<? extends RetrievalCandidateHit> candidates,
                                     int topK,
                                     RetrievalScoreType scoreType) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Long> documentIds = candidates.stream()
                .map(RetrievalCandidateHit::documentId)
                .distinct()
                .toList();
        Map<Long, KnowledgeDocument> documentsById = documentService.findByIds(documentIds).stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));

        List<RetrievalHit> visible = new ArrayList<>(Math.min(topK, candidates.size()));
        Set<Long> orphanDocumentIds = new LinkedHashSet<>();
        Set<Long> hiddenDocumentIds = new LinkedHashSet<>();
        for (RetrievalCandidateHit candidate : candidates) {
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
                    scoreType,
                    candidate.content(),
                    candidate.pageNo(),
                    candidate.sectionTitle()));
            if (visible.size() == topK) {
                break;
            }
        }
        if (!orphanDocumentIds.isEmpty()) {
            log.warn("Filtered stale Milvus candidates with orphan document IDs: "
                            + "knowledgeBaseId={}, documentIds={}",
                    knowledgeBaseId, orphanDocumentIds);
        }
        if (!hiddenDocumentIds.isEmpty()) {
            log.warn("Filtered Milvus candidates not visible from MySQL document state: "
                            + "knowledgeBaseId={}, documentIds={}",
                    knowledgeBaseId, hiddenDocumentIds);
        }
        return List.copyOf(visible);
    }
}
