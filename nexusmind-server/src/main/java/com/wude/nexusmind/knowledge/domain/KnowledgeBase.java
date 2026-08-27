package com.wude.nexusmind.knowledge.domain;

import java.time.LocalDateTime;

public class KnowledgeBase {

    private Long id;
    private String name;
    private String description;
    private String embeddingModel;
    private Integer embeddingDimension;
    private KnowledgeBaseStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public KnowledgeBase() {
    }

    public KnowledgeBase(String name, String description, String embeddingModel,
                         Integer embeddingDimension, KnowledgeBaseStatus status) {
        this.name = name;
        this.description = description;
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(Integer embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public KnowledgeBaseStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeBaseStatus status) {
        this.status = status;
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
