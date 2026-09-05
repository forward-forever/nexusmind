package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.config.StorageProperties;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.parser.DocumentParserRegistry;
import com.wude.nexusmind.knowledge.storage.DocumentStorage;
import com.wude.nexusmind.knowledge.storage.StagedDocument;
import com.wude.nexusmind.knowledge.storage.StoredDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final DocumentParserRegistry parserRegistry;
    private final DocumentStorage documentStorage;
    private final long maximumFileSize;

    public DocumentIngestionService(KnowledgeBaseService knowledgeBaseService,
                                    DocumentService documentService,
                                    DocumentParserRegistry parserRegistry,
                                    DocumentStorage documentStorage,
                                    StorageProperties storageProperties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
        this.parserRegistry = parserRegistry;
        this.documentStorage = documentStorage;
        this.maximumFileSize = storageProperties.maxFileSize().toBytes();
    }

    public DocumentUploadResult upload(long knowledgeBaseId, MultipartFile file) {
        knowledgeBaseService.get(knowledgeBaseId);
        String originalFileName = requireValidUpload(file);
        parserRegistry.requireSupported(originalFileName);

        StagedDocument stagedDocument = null;
        StoredDocument storedDocument = null;
        try {
            stagedDocument = documentStorage.stage(file.getInputStream());
            if (stagedDocument.fileSize() == 0) {
                throw new IllegalArgumentException("Uploaded file is empty");
            }

            KnowledgeDocument duplicate = documentService
                    .findByKnowledgeBaseAndSha256(knowledgeBaseId, stagedDocument.sha256())
                    .orElse(null);
            if (duplicate != null) {
                documentStorage.discard(stagedDocument);
                return new DocumentUploadResult(duplicate, true);
            }

            storedDocument = documentStorage.commit(stagedDocument, knowledgeBaseId, originalFileName);
            KnowledgeDocument document = new KnowledgeDocument(
                    knowledgeBaseId,
                    originalFileName,
                    storedDocument.relativePath(),
                    safeContentType(file.getContentType()),
                    stagedDocument.fileSize(),
                    stagedDocument.sha256()
            );
            documentService.register(document);
            return new DocumentUploadResult(documentService.get(document.getId()), false);
        } catch (DuplicateKeyException exception) {
            KnowledgeDocument existing = stagedDocument == null ? null : documentService
                    .findByKnowledgeBaseAndSha256(knowledgeBaseId, stagedDocument.sha256())
                    .orElse(null);
            if (existing == null) {
                cleanupFailedCommit(storedDocument, null);
                throw exception;
            }
            cleanupConcurrentDuplicate(storedDocument, existing);
            return new DocumentUploadResult(existing, true);
        } catch (IOException exception) {
            cleanupFailedCommit(storedDocument, stagedDocument);
            throw new com.wude.nexusmind.knowledge.exception.DocumentStorageException(
                    "Failed to read uploaded document", exception);
        } catch (RuntimeException exception) {
            cleanupFailedCommit(storedDocument, stagedDocument);
            throw exception;
        } finally {
            documentStorage.discard(stagedDocument);
        }
    }

    private String requireValidUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        if (file.getSize() > maximumFileSize) {
            throw new com.wude.nexusmind.knowledge.exception.DocumentTooLargeException(maximumFileSize);
        }
        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("Original file name is required");
        }
        if (originalFileName.length() > 255) {
            throw new IllegalArgumentException("Original file name exceeds 255 characters");
        }
        return originalFileName;
    }

    private static String safeContentType(String contentType) {
        if (contentType == null || contentType.isBlank() || contentType.length() > 128) {
            return DEFAULT_CONTENT_TYPE;
        }
        return contentType;
    }

    private void cleanupConcurrentDuplicate(StoredDocument storedDocument, KnowledgeDocument existing) {
        if (storedDocument != null
                && storedDocument.created()
                && !storedDocument.relativePath().equals(existing.getStoragePath())) {
            deleteStoredQuietly(storedDocument.relativePath());
        }
    }

    private void cleanupFailedCommit(StoredDocument storedDocument, StagedDocument stagedDocument) {
        if (storedDocument != null && storedDocument.created()) {
            deleteStoredQuietly(storedDocument.relativePath());
        }
        documentStorage.discard(stagedDocument);
    }

    private void deleteStoredQuietly(String relativePath) {
        try {
            documentStorage.delete(relativePath);
        } catch (RuntimeException cleanupException) {
            log.warn("Failed to clean up stored upload at relative path {}", relativePath, cleanupException);
        }
    }
}
