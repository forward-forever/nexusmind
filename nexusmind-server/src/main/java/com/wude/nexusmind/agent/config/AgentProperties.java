package com.wude.nexusmind.agent.config;

import com.wude.nexusmind.rag.retrieval.RetrieverType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("nexusmind.agent")
public record AgentProperties(
        boolean enabled,
        @Min(1) int maxToolCalls,
        @NotNull Duration maxDuration,
        @Valid @NotNull KnowledgeSearch knowledgeSearch,
        @Valid @NotNull DocumentContext documentContext) {

    public AgentProperties {
        if (maxDuration != null && (maxDuration.isZero() || maxDuration.isNegative())) {
            throw new IllegalArgumentException("Agent max duration must be positive");
        }
    }

    public record KnowledgeSearch(
            @NotNull RetrieverType retriever,
            @Min(1) int topK) {
    }

    public record DocumentContext(
            @Min(0) int beforeChunks,
            @Min(0) int afterChunks) {
    }
}
