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

    /**
     * <a href="https://milvus.io/docs/zh/v2.6.x/hnsw.md#HNSW">HNSW</a>
     * HNSW parameters
     * @param m HNSW m parameter 图中每个节点在层次结构的每个层级所能拥有的最大边数或连接数，M 越高，图的密度就越大，搜索结果的召回率和准确率也就越高，因为有更多的路径可以探索，但同时也会消耗更多内存，并由于连接数的增加而减慢插入时间。
     * @param efConstruction HNSW efConstruction parameter 索引构建过程中考虑的候选节点数量。efConstruction 越高，图的质量越好，但需要更多时间来构建。
     * @param ef HNSW ef parameter 搜索过程中评估的邻居数量。增加ef 可以提高找到最近邻居的可能性，但会减慢搜索过程。
     */
    public record Hnsw(int m, int efConstruction, int ef) {
        public Hnsw {
            if (m <= 0 || efConstruction <= 0 || ef <= 0) {
                throw new IllegalArgumentException("Milvus HNSW parameters must be positive");
            }
        }
    }

    /**
     * <a href="https://milvus.io/docs/zh/v2.6.x/full-text-search.md">BM25</a>
     * BM25 parameters
     * @param invertedIndexAlgo Inverted index algorithm
     * @param k1 BM25 k1 parameter 控制词频饱和度。数值越大，术语频率在文档排名中的重要性就越高。取值范围[1.2, 2.0].
     * @param b BM25 b parameter 控制文档长度的标准化程度。通常使用 0 到 1 之间的值，默认值为 0.75 左右。值为 1 表示不进行长度归一化，值为 0 表示完全归一化。
     * @param analyzer BM25 analyzer
     */
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
