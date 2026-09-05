package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.chunk.DocumentChunker;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.DocumentParsingException;
import com.wude.nexusmind.knowledge.exception.DocumentStorageException;
import com.wude.nexusmind.knowledge.parser.DocumentParser;
import com.wude.nexusmind.knowledge.parser.DocumentParserRegistry;
import com.wude.nexusmind.knowledge.parser.ParsedDocument;
import com.wude.nexusmind.knowledge.storage.DocumentStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class DocumentProcessingService {

    private final DocumentService documentService;
    private final DocumentStorage documentStorage;
    private final DocumentParserRegistry parserRegistry;
    private final DocumentChunker documentChunker;

    public DocumentProcessingService(DocumentService documentService,
                                     DocumentStorage documentStorage,
                                     DocumentParserRegistry parserRegistry,
                                     DocumentChunker documentChunker) {
        this.documentService = documentService;
        this.documentStorage = documentStorage;
        this.parserRegistry = parserRegistry;
        this.documentChunker = documentChunker;
    }

    public KnowledgeDocument process(long documentId) {
        documentService.markProcessing(documentId);
        try {
            KnowledgeDocument document = documentService.get(documentId);
            Path storedFile = documentStorage.resolve(document.getStoragePath());
            DocumentParser parser = parserRegistry.parserFor(document.getOriginalFileName());
            ParsedDocument parsedDocument = parser.parse(storedFile);
            List<KnowledgeChunk> chunks = documentChunker.chunk(
                    parsedDocument, document.getKnowledgeBaseId(), document.getId());
            if (chunks.isEmpty()) {
                throw new DocumentParsingException("Document does not contain usable text for chunking");
            }
            documentService.replaceChunksAndMarkReady(documentId, chunks);
            return documentService.get(documentId);
        } catch (RuntimeException processingFailure) {
            markFailed(documentId, processingFailure);
            throw processingFailure;
        }
    }

    private void markFailed(long documentId, RuntimeException processingFailure) {
        String failureMessage;
        if (processingFailure instanceof DocumentParsingException
                || processingFailure instanceof DocumentStorageException) {
            failureMessage = processingFailure.getMessage();
        } else {
            failureMessage = "Document processing failed";
        }
        if (failureMessage == null || failureMessage.isBlank()) {
            failureMessage = "Document processing failed";
        }
        if (failureMessage.length() > 2000) {
            failureMessage = failureMessage.substring(0, 2000);
        }
        try {
            documentService.markFailed(documentId, failureMessage);
        } catch (RuntimeException statusFailure) {
            processingFailure.addSuppressed(statusFailure);
        }
    }
}
