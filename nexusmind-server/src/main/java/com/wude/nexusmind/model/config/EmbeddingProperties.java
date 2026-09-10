package com.wude.nexusmind.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusmind.ai.embedding")
public record EmbeddingProperties(String model, int dimension, int batchSize, int maxBatchChars) {

    public EmbeddingProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding model is required");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("Embedding dimension must be positive");
        }
        if (batchSize <= 0 || batchSize > 20) {
            throw new IllegalArgumentException("Embedding batch-size must be between 1 and 20");
        }
        if (maxBatchChars <= 0) {
            throw new IllegalArgumentException("Embedding max-batch-chars must be positive");
        }
    }
}
