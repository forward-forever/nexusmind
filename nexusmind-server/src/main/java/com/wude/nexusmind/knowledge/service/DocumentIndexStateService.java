package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.DocumentNotFoundException;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentIndexStateException;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class DocumentIndexStateService {

    private final KnowledgeDocumentMapper documentMapper;

    public DocumentIndexStateService(KnowledgeDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Transactional
    public void markIndexing(long documentId) {
        KnowledgeDocument document = findForUpdate(documentId);
        DocumentIndexStatus current = document.getIndexStatus();
        if (document.getStatus() != DocumentStatus.READY
                || (current != DocumentIndexStatus.NOT_INDEXED && current != DocumentIndexStatus.FAILED)) {
            throw new InvalidDocumentIndexStateException(
                    documentId, document.getStatus(), current, "start indexing");
        }
        update(documentId, DocumentIndexStatus.INDEXING, null);
    }

    @Transactional
    public void markIndexed(long documentId) {
        KnowledgeDocument document = findForUpdate(documentId);
        requireIndexing(document, "be marked indexed");
        update(documentId, DocumentIndexStatus.INDEXED, null);
    }

    @Transactional
    public void markFailed(long documentId, String errorMessage) {
        KnowledgeDocument document = findForUpdate(documentId);
        requireIndexing(document, "be marked index failed");
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("Index failure message is required");
        }
        if (errorMessage.length() > 2000) {
            throw new IllegalArgumentException("Index failure message exceeds 2000 characters");
        }
        update(documentId, DocumentIndexStatus.FAILED, errorMessage);
    }

    private KnowledgeDocument findForUpdate(long documentId) {
        return documentMapper.findByIdForUpdate(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private static void requireIndexing(KnowledgeDocument document, String operation) {
        if (document.getStatus() != DocumentStatus.READY
                || document.getIndexStatus() != DocumentIndexStatus.INDEXING) {
            throw new InvalidDocumentIndexStateException(
                    document.getId(), document.getStatus(), document.getIndexStatus(), operation);
        }
    }

    private void update(long documentId, DocumentIndexStatus status, String errorMessage) {
        if (documentMapper.updateIndexStatus(documentId, status, errorMessage) != 1) {
            throw new DocumentNotFoundException(documentId);
        }
    }
}
