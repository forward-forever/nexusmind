package com.wude.nexusmind.knowledge.task;

import java.time.LocalDateTime;

public class KnowledgeDocumentTask {

    private Long id;
    private Long knowledgeBaseId;
    private Long documentId;
    private DocumentTaskType taskType;
    private DocumentTaskStatus status;
    private Integer attemptCount;
    private Integer recoveryCount;
    private String workerId;
    private String runToken;
    private String lastError;
    private LocalDateTime enqueuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public DocumentTaskType getTaskType() { return taskType; }
    public void setTaskType(DocumentTaskType taskType) { this.taskType = taskType; }
    public DocumentTaskStatus getStatus() { return status; }
    public void setStatus(DocumentTaskStatus status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getRecoveryCount() { return recoveryCount; }
    public void setRecoveryCount(Integer recoveryCount) { this.recoveryCount = recoveryCount; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getRunToken() { return runToken; }
    public void setRunToken(String runToken) { this.runToken = runToken; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(LocalDateTime enqueuedAt) { this.enqueuedAt = enqueuedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(LocalDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
