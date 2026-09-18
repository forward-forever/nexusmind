package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.model.DocumentContextToolResult;
import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;

import java.util.List;
import java.util.Optional;

public final class DocumentContextService {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final AgentToolResultBudgeter resultBudgeter;

    public DocumentContextService(KnowledgeChunkMapper chunkMapper,
                                  KnowledgeDocumentMapper documentMapper,
                                  AgentToolResultBudgeter resultBudgeter) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.resultBudgeter = resultBudgeter;
    }

    public DocumentContextToolResult load(AgentRunContext runContext,
                                          String sourceId,
                                          int beforeChunks,
                                          int afterChunks) {
        Optional<Long> chunkId = runContext.sourceRegistry().resolveChunkId(sourceId);
        if (chunkId.isEmpty()) {
            return resultBudgeter.budgetEmptyContext(
                    runContext, DocumentContextToolResult.unknownSource(sourceId));
        }

        Optional<KnowledgeChunk> targetOptional = chunkMapper.findById(chunkId.get());
        if (targetOptional.isEmpty()) {
            return unavailable(runContext, sourceId);
        }
        KnowledgeChunk target = targetOptional.get();
        if (!belongsToKnowledgeBase(target, runContext.knowledgeBaseId())) {
            return unavailable(runContext, sourceId);
        }

        Optional<KnowledgeDocument> documentOptional = documentMapper.findById(target.getDocumentId());
        if (documentOptional.isEmpty()) {
            return unavailable(runContext, sourceId);
        }
        KnowledgeDocument document = documentOptional.get();
        if (!isVisible(document, runContext.knowledgeBaseId())) {
            return unavailable(runContext, sourceId);
        }

        int fromIndex = Math.max(0, target.getChunkIndex() - beforeChunks);
        int toIndex = Math.addExact(target.getChunkIndex(), afterChunks);
        List<KnowledgeChunk> chunks = chunkMapper.findByDocumentIdAndChunkIndexBetween(
                target.getDocumentId(), fromIndex, toIndex);
        boolean targetFound = false;
        for (KnowledgeChunk chunk : chunks) {
            requireSameDocumentAndKnowledgeBase(chunk, target, runContext.knowledgeBaseId());
            boolean isTarget = chunk.getId().equals(target.getId());
            targetFound |= isTarget;
        }
        if (!targetFound) {
            throw new IllegalStateException("Document context query did not return the target chunk");
        }
        return resultBudgeter.budgetDocumentContext(
                runContext, sourceId, document, chunks, target.getId());
    }

    private DocumentContextToolResult unavailable(AgentRunContext runContext, String sourceId) {
        return resultBudgeter.budgetEmptyContext(
                runContext, DocumentContextToolResult.sourceUnavailable(sourceId));
    }

    private static boolean belongsToKnowledgeBase(KnowledgeChunk chunk, long knowledgeBaseId) {
        return chunk.getKnowledgeBaseId() != null
                && chunk.getKnowledgeBaseId() == knowledgeBaseId;
    }

    private static boolean isVisible(KnowledgeDocument document, long knowledgeBaseId) {
        return document.getKnowledgeBaseId() != null
                && document.getKnowledgeBaseId() == knowledgeBaseId
                && document.getStatus() == DocumentStatus.READY
                && document.getIndexStatus() == DocumentIndexStatus.INDEXED;
    }

    private static void requireSameDocumentAndKnowledgeBase(KnowledgeChunk chunk,
                                                             KnowledgeChunk target,
                                                             long knowledgeBaseId) {
        if (!target.getDocumentId().equals(chunk.getDocumentId())
                || chunk.getKnowledgeBaseId() == null
                || chunk.getKnowledgeBaseId() != knowledgeBaseId) {
            throw new IllegalStateException("Document context query crossed a document or knowledge-base boundary");
        }
    }
}
