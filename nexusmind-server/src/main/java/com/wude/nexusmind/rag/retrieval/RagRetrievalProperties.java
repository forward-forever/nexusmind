package com.wude.nexusmind.rag.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusmind.rag")
public record RagRetrievalProperties(RetrieverType retriever) {

    public RagRetrievalProperties {
        if (retriever == null) {
            throw new IllegalArgumentException("Default RAG retriever is required");
        }
    }
}
