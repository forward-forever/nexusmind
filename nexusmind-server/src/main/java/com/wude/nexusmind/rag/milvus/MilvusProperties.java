package com.wude.nexusmind.rag.milvus;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nexusmind.milvus")
public record MilvusProperties(
        boolean enabled,
        String uri,
        int contentMaxLength,
        Duration readyTimeout,
        Hnsw hnsw,
        Bm25 bm25
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
        if (bm25 == null) {
            throw new IllegalArgumentException("Milvus BM25 configuration is required");
        }
    }

    public record Hnsw(int m, int efConstruction, int ef) {
        public Hnsw {
            if (m <= 0 || efConstruction <= 0 || ef <= 0) {
                throw new IllegalArgumentException("Milvus HNSW parameters must be positive");
            }
        }
    }

    public record Bm25(String invertedIndexAlgo, double k1, double b, String analyzer) {
        public Bm25 {
            if (invertedIndexAlgo == null || invertedIndexAlgo.isBlank()) {
                throw new IllegalArgumentException("Milvus BM25 inverted index algorithm is required");
            }
            if (k1 <= 0) {
                throw new IllegalArgumentException("Milvus BM25 k1 must be positive");
            }
            if (b < 0 || b > 1) {
                throw new IllegalArgumentException("Milvus BM25 b must be between 0 and 1");
            }
            if (analyzer == null || analyzer.isBlank()) {
                throw new IllegalArgumentException("Milvus BM25 analyzer is required");
            }
        }
    }
}
