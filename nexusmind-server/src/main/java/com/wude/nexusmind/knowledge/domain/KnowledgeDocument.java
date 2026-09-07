package com.wude.nexusmind.knowledge.domain;

import java.time.LocalDateTime;

public class KnowledgeDocument {

    private Long id;
    private Long knowledgeBaseId;
    private String originalFileName;
    private String storagePath;
    private String contentType;
    private Long fileSize;
    private String fileSha256;
    private DocumentStatus status;
    private Integer chunkCount;
    private String errorMessage;
    private DocumentIndexStatus indexStatus;
    private String indexErrorMessage;
    private LocalDateTime indexedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public KnowledgeDocument() {
    }

    public KnowledgeDocument(Long knowledgeBaseId, String originalFileName, String storagePath,
                             String contentType, Long fileSize, String fileSha256) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.originalFileName = originalFileName;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.fileSha256 = fileSha256;
        this.status = DocumentStatus.UPLOADED;
        this.chunkCount = 0;
        this.indexStatus = DocumentIndexStatus.NOT_INDEXED;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public void setFileSha256(String fileSha256) {
        this.fileSha256 = fileSha256;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public DocumentIndexStatus getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(DocumentIndexStatus indexStatus) {
        this.indexStatus = indexStatus;
    }

    public String getIndexErrorMessage() {
        return indexErrorMessage;
    }

    public void setIndexErrorMessage(String indexErrorMessage) {
        this.indexErrorMessage = indexErrorMessage;
    }

    public LocalDateTime getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(LocalDateTime indexedAt) {
        this.indexedAt = indexedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
