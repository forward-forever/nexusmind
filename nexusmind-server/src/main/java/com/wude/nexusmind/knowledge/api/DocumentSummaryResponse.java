package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;

import java.time.LocalDateTime;

public record DocumentSummaryResponse(
        Long id,
        String originalFileName,
        String contentType,
        Long fileSize,
        DocumentStatus status,
        Integer chunkCount,
        DocumentIndexStatus indexStatus,
        LocalDateTime indexedAt,
        String errorMessage,
        String indexErrorMessage,
        LocalDateTime createdAt
) {

    static DocumentSummaryResponse from(KnowledgeDocument document) {
        return new DocumentSummaryResponse(
                document.getId(),
                document.getOriginalFileName(),
                document.getContentType(),
                document.getFileSize(),
                document.getStatus(),
                document.getChunkCount(),
                document.getIndexStatus(),
                document.getIndexedAt(),
                document.getErrorMessage(),
                document.getIndexErrorMessage(),
                document.getCreatedAt());
    }
}
