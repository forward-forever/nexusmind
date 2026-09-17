package com.wude.nexusmind.agent.config;

import com.wude.nexusmind.rag.retrieval.RetrieverType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("nexusmind.agent")
public record AgentProperties(
        boolean enabled,
        @Min(1) int maxToolCalls,
        @NotNull Duration maxDuration,
        @Valid @NotNull KnowledgeSearch knowledgeSearch,
        @Valid @NotNull DocumentContext documentContext,
        @Valid @NotNull Memory memory,
        @Valid @NotNull SessionConcurrency sessionConcurrency) {

    public AgentProperties(boolean enabled,
                           int maxToolCalls,
                           Duration maxDuration,
                           KnowledgeSearch knowledgeSearch,
                           DocumentContext documentContext,
                           Memory memory) {
        this(enabled, maxToolCalls, maxDuration, knowledgeSearch, documentContext, memory,
                new SessionConcurrency(Duration.ofSeconds(45)));
    }

    @ConstructorBinding
    public AgentProperties {
        if (maxDuration != null && (maxDuration.isZero() || maxDuration.isNegative())) {
            throw new IllegalArgumentException("Agent max duration must be positive");
        }
        if (sessionConcurrency != null && maxDuration != null
                && sessionConcurrency.leaseDuration().compareTo(maxDuration) <= 0) {
            throw new IllegalArgumentException("Agent session lease duration must exceed max duration");
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

    public record Memory(@Min(1) int maxMessages) {
    }

    public record SessionConcurrency(@NotNull Duration leaseDuration) {
        public SessionConcurrency {
            if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("Agent session lease duration must be positive");
            }
        }
    }
}
