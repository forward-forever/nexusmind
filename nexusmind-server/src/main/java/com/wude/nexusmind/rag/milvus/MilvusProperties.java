package com.wude.nexusmind.rag.milvus;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nexusmind.milvus")
public record MilvusProperties(
        boolean enabled,
        String uri,
        int contentMaxLength,
        Duration readyTimeout,
        Hnsw hnsw
) {

    public MilvusProperties {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Milvus URI is required");
        }
        if (contentMaxLength <= 0 || contentMaxLength > 65_535) {
            throw new IllegalArgumentException("Milvus content max length must be between 1 and 65535");
        }
        if (readyTimeout == null || readyTimeout.isNegative() || readyTimeout.isZero()) {
            throw new IllegalArgumentException("Milvus ready timeout must be positive");
        }
        if (hnsw == null) {
            throw new IllegalArgumentException("Milvus HNSW configuration is required");
        }
    }

    public record Hnsw(int m, int efConstruction, int ef) {
        public Hnsw {
            if (m <= 0 || efConstruction <= 0 || ef <= 0) {
                throw new IllegalArgumentException("Milvus HNSW parameters must be positive");
            }
        }
    }
}
