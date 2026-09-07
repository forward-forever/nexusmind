package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.exception.KnowledgeBaseNotFoundException;
import com.wude.nexusmind.knowledge.mapper.KnowledgeBaseMapper;
import com.wude.nexusmind.model.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
@Transactional(readOnly = true)
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingProperties embeddingProperties;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                EmbeddingProperties embeddingProperties) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.embeddingProperties = embeddingProperties;
    }

    @Transactional
    public long create(String name, String description) {
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                name,
                description,
                embeddingProperties.model(),
                embeddingProperties.dimension(),
                KnowledgeBaseStatus.ACTIVE
        );
        requireValid(knowledgeBase, false);
        knowledgeBaseMapper.insert(knowledgeBase);
        if (knowledgeBase.getId() == null) {
            throw new IllegalStateException("Knowledge base ID was not generated");
        }
        return knowledgeBase.getId();
    }

    public KnowledgeBase get(long id) {
        return knowledgeBaseMapper.findById(id)
                .orElseThrow(() -> new KnowledgeBaseNotFoundException(id));
    }

    public List<KnowledgeBase> list() {
        return List.copyOf(knowledgeBaseMapper.findAll());
    }

    @Transactional
    public void update(KnowledgeBase knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.getId() == null) {
            throw new IllegalArgumentException("Knowledge base and ID are required");
        }
        KnowledgeBase existing = get(knowledgeBase.getId());
        knowledgeBase.setEmbeddingModel(existing.getEmbeddingModel());
        knowledgeBase.setEmbeddingDimension(existing.getEmbeddingDimension());
        requireValid(knowledgeBase, true);
        if (knowledgeBaseMapper.update(knowledgeBase) != 1) {
            throw new KnowledgeBaseNotFoundException(knowledgeBase.getId());
        }
    }

    private static void requireValid(KnowledgeBase knowledgeBase, boolean idRequired) {
        if (knowledgeBase == null) {
            throw new IllegalArgumentException("Knowledge base is required");
        }
        if (idRequired && knowledgeBase.getId() == null) {
            throw new IllegalArgumentException("Knowledge base ID is required");
        }
        requireText(knowledgeBase.getName(), "Knowledge base name", 128);
        requireText(knowledgeBase.getEmbeddingModel(), "Embedding model", 128);
        if (knowledgeBase.getEmbeddingDimension() == null || knowledgeBase.getEmbeddingDimension() <= 0) {
            throw new IllegalArgumentException("Embedding dimension must be positive");
        }
        if (knowledgeBase.getDescription() != null && knowledgeBase.getDescription().length() > 1024) {
            throw new IllegalArgumentException("Knowledge base description exceeds 1024 characters");
        }
        if (idRequired && knowledgeBase.getStatus() == null) {
            throw new IllegalArgumentException("Knowledge base status is required");
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
