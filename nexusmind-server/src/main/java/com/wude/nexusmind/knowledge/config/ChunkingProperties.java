package com.wude.nexusmind.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusmind.chunking")
public record ChunkingProperties(int chunkSizeChars, int chunkOverlapChars) {

    public ChunkingProperties {
        if (chunkSizeChars <= 0) {
            throw new IllegalArgumentException("chunk-size-chars must be positive");
        }
        if (chunkOverlapChars < 0 || chunkOverlapChars >= chunkSizeChars) {
            throw new IllegalArgumentException("chunk-overlap-chars must satisfy 0 <= overlap < chunk size");
        }
    }
}
