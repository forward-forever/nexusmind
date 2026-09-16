package com.wude.nexusmind.agent.memory.domain;

import java.time.LocalDateTime;

public class AgentMessageEntity {

    private Long id;
    private String sessionId;
    private AgentMessageRole role;
    private String content;
    private LocalDateTime createdAt;

    public AgentMessageEntity() {
    }

    public AgentMessageEntity(String sessionId, AgentMessageRole role, String content) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public AgentMessageRole getRole() {
        return role;
    }

    public void setRole(AgentMessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
