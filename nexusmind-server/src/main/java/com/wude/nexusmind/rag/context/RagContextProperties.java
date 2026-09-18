package com.wude.nexusmind.rag.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusmind.rag.context")
public record RagContextProperties(int maxTokens) {

    public RagContextProperties {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("RAG context max tokens must be positive");
        }
    }
}
