package com.wude.nexusmind.agent.memory.domain;

import java.time.LocalDateTime;

public class AgentSessionEntity {

    private String sessionId;
    private Long knowledgeBaseId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AgentSessionEntity() {
    }

    public AgentSessionEntity(String sessionId, Long knowledgeBaseId) {
        this.sessionId = sessionId;
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
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
