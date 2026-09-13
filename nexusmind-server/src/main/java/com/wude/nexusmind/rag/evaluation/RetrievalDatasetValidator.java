package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.KnowledgeBaseNotFoundException;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RetrievalDatasetValidator {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkService chunkService;
    private final DocumentService documentService;

    public RetrievalDatasetValidator(KnowledgeBaseService knowledgeBaseService,
                                     ChunkService chunkService,
                                     DocumentService documentService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkService = chunkService;
        this.documentService = documentService;
    }

    public void validate(RetrievalEvaluationDataset dataset) {
        if (dataset == null || dataset.cases().isEmpty()) {
            throw new DatasetValidationException("Dataset must contain at least one evaluation case");
        }
        Set<Long> knowledgeBaseIds = dataset.cases().stream()
                .map(RetrievalEvaluationCase::knowledgeBaseId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (long knowledgeBaseId : knowledgeBaseIds) {
            try {
                knowledgeBaseService.get(knowledgeBaseId);
            } catch (KnowledgeBaseNotFoundException exception) {
                throw new DatasetValidationException(
                        "Dataset references missing knowledge base: " + knowledgeBaseId, exception);
            }
        }

        Set<Long> expectedChunkIds = dataset.cases().stream()
                .flatMap(evaluationCase -> evaluationCase.relevantChunkIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, KnowledgeChunk> chunksById = chunkService.findByIds(expectedChunkIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, Function.identity()));
        Set<Long> missingChunkIds = new LinkedHashSet<>(expectedChunkIds);
        missingChunkIds.removeAll(chunksById.keySet());
        if (!missingChunkIds.isEmpty()) {
            throw new DatasetValidationException(
                    "Dataset references missing knowledge chunks: " + missingChunkIds);
        }

        Set<Long> documentIds = chunksById.values().stream()
                .map(KnowledgeChunk::getDocumentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, KnowledgeDocument> documentsById = documentService.findByIds(documentIds).stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));

        for (RetrievalEvaluationCase evaluationCase : dataset.cases()) {
            for (long chunkId : evaluationCase.relevantChunkIds()) {
                KnowledgeChunk chunk = chunksById.get(chunkId);
                if (!Long.valueOf(evaluationCase.knowledgeBaseId()).equals(chunk.getKnowledgeBaseId())) {
                    throw new DatasetValidationException(
                            "Chunk %d does not belong to knowledge base %d"
                                    .formatted(chunkId, evaluationCase.knowledgeBaseId()));
                }
                KnowledgeDocument document = documentsById.get(chunk.getDocumentId());
                if (document == null) {
                    throw new DatasetValidationException(
                            "Chunk %d references missing document %d"
                                    .formatted(chunkId, chunk.getDocumentId()));
                }
                if (!Long.valueOf(evaluationCase.knowledgeBaseId()).equals(document.getKnowledgeBaseId())
                        || document.getStatus() != DocumentStatus.READY
                        || document.getIndexStatus() != DocumentIndexStatus.INDEXED) {
                    throw new DatasetValidationException(
                            "Golden chunk %d belongs to a document that is not retrieval-visible"
                                    .formatted(chunkId));
                }
            }
        }
    }
}
