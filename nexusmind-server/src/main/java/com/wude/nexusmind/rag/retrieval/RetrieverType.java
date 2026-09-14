package com.wude.nexusmind.rag.retrieval;

public enum RetrieverType {
    /**
     * Dense retriever
     */
    DENSE,
    /**
     * BM25 retriever
     */
    BM25,
    /**
     * Hybrid retriever with RRF scoring
     */
    HYBRID_RRF,
    /**
     * Hybrid retriever with reranking
     */
    HYBRID_RERANK
}
