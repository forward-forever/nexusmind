package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.DocumentNotFoundException;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentStateException;
import com.wude.nexusmind.knowledge.exception.KnowledgeBaseNotFoundException;
import com.wude.nexusmind.knowledge.mapper.KnowledgeBaseMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
@Transactional(readOnly = true)
public class DocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;

    public DocumentService(KnowledgeBaseMapper knowledgeBaseMapper,
                           KnowledgeDocumentMapper documentMapper,
                           KnowledgeChunkMapper chunkMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public long register(KnowledgeDocument document) {
        requireValidDocument(document);
        if (knowledgeBaseMapper.findById(document.getKnowledgeBaseId()).isEmpty()) {
            throw new KnowledgeBaseNotFoundException(document.getKnowledgeBaseId());
        }
        if (document.getStatus() == null) {
            document.setStatus(DocumentStatus.UPLOADED);
        }
        if (document.getChunkCount() == null) {
            document.setChunkCount(0);
        }
        documentMapper.insert(document);
        if (document.getId() == null) {
            throw new IllegalStateException("Document ID was not generated");
        }
        return document.getId();
    }

    public KnowledgeDocument get(long id) {
        return findRequired(id);
    }

    public List<KnowledgeDocument> listByKnowledgeBase(long knowledgeBaseId) {
        return List.copyOf(documentMapper.findByKnowledgeBaseId(knowledgeBaseId));
    }

    public List<KnowledgeDocument> findByIds(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(documentMapper.findByIds(documentIds));
    }

    public Optional<KnowledgeDocument> findByKnowledgeBaseAndSha256(long knowledgeBaseId, String sha256) {
        return documentMapper.findByKnowledgeBaseIdAndSha256(knowledgeBaseId, sha256);
    }

    @Transactional
    public void markProcessing(long documentId) {
        KnowledgeDocument document = findRequiredForUpdate(documentId);
        if (document.getStatus() != DocumentStatus.UPLOADED && document.getStatus() != DocumentStatus.FAILED) {
            throw new InvalidDocumentStateException(documentId, document.getStatus(), "start processing");
        }
        updateStatus(documentId, DocumentStatus.PROCESSING, document.getChunkCount(), null);
    }

    @Transactional
    public void markFailed(long documentId, String errorMessage) {
        KnowledgeDocument document = findRequiredForUpdate(documentId);
        if (document.getStatus() != DocumentStatus.PROCESSING) {
            throw new InvalidDocumentStateException(documentId, document.getStatus(), "be marked failed");
        }
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("Failure message is required");
        }
        if (errorMessage.length() > 2000) {
            throw new IllegalArgumentException("Failure message exceeds 2000 characters");
        }
        updateStatus(documentId, DocumentStatus.FAILED, document.getChunkCount(), errorMessage);
    }

    @Transactional
    public void replaceChunksAndMarkReady(long documentId, List<KnowledgeChunk> chunks) {
        KnowledgeDocument document = findRequiredForUpdate(documentId);
        if (document.getStatus() != DocumentStatus.PROCESSING) {
            throw new InvalidDocumentStateException(documentId, document.getStatus(), "replace chunks");
        }
        if (chunks == null) {
            throw new IllegalArgumentException("Chunks are required");
        }
        for (KnowledgeChunk chunk : chunks) {
            requireChunkForDocument(chunk, document);
        }

        chunkMapper.deleteByDocumentId(documentId);
        if (!chunks.isEmpty()) {
            chunkMapper.batchInsert(chunks);
        }
        updateStatus(documentId, DocumentStatus.READY, chunks.size(), null);
    }

    private KnowledgeDocument findRequired(long documentId) {
        return documentMapper.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private KnowledgeDocument findRequiredForUpdate(long documentId) {
        return documentMapper.findByIdForUpdate(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private void updateStatus(long documentId, DocumentStatus status, Integer chunkCount, String errorMessage) {
        int safeChunkCount = chunkCount == null ? 0 : chunkCount;
        if (documentMapper.updateStatus(documentId, status, safeChunkCount, errorMessage) != 1) {
            throw new DocumentNotFoundException(documentId);
        }
    }

    private static void requireValidDocument(KnowledgeDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Document is required");
        }
        if (document.getKnowledgeBaseId() == null) {
            throw new IllegalArgumentException("Knowledge base ID is required");
        }
        requireText(document.getOriginalFileName(), "Original file name", 255);
        requireText(document.getContentType(), "Content type", 128);
        if (document.getStoragePath() != null && document.getStoragePath().length() > 1024) {
            throw new IllegalArgumentException("Storage path exceeds 1024 characters");
        }
        if (document.getFileSize() == null || document.getFileSize() < 0) {
            throw new IllegalArgumentException("File size must be zero or positive");
        }
        if (document.getFileSha256() == null || !document.getFileSha256().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("File SHA-256 must contain exactly 64 hexadecimal characters");
        }
    }

    private static void requireChunkForDocument(KnowledgeChunk chunk, KnowledgeDocument document) {
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }
        if (!Objects.equals(chunk.getKnowledgeBaseId(), document.getKnowledgeBaseId())) {
            throw new IllegalArgumentException("Chunk knowledge base does not match its document");
        }
        if (!Objects.equals(chunk.getDocumentId(), document.getId())) {
            throw new IllegalArgumentException("Chunk document ID does not match its document");
        }
        if (chunk.getChunkIndex() == null || chunk.getChunkIndex() < 0) {
            throw new IllegalArgumentException("Chunk index must be zero or positive");
        }
        if (chunk.getContent() == null || chunk.getContent().isBlank()) {
            throw new IllegalArgumentException("Chunk content is required");
        }
        if (chunk.getCharCount() == null || chunk.getCharCount() < 0) {
            throw new IllegalArgumentException("Chunk character count must be zero or positive");
        }
        if (chunk.getTokenCount() != null && chunk.getTokenCount() < 0) {
            throw new IllegalArgumentException("Chunk token count must be zero, positive, or null");
        }
    }

    private static void requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
    }
}
