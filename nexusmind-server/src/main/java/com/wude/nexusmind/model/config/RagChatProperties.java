package com.wude.nexusmind.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nexusmind.ai.chat")
public record RagChatProperties(
        String model,
        double temperature,
        int defaultTopK,
        int maxTopK,
        int maxContextChars,
        Duration streamTimeout
) {

    public RagChatProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Chat model is required");
        }
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("Chat temperature must be between 0 and 2");
        }
        if (defaultTopK < 1 || maxTopK < defaultTopK || maxTopK > 20) {
            throw new IllegalArgumentException("Chat topK must satisfy 1 <= default <= max <= 20");
        }
        if (maxContextChars <= 0) {
            throw new IllegalArgumentException("Chat max-context-chars must be positive");
        }
        if (streamTimeout == null || streamTimeout.isZero() || streamTimeout.isNegative()) {
            throw new IllegalArgumentException("Chat stream-timeout must be positive");
        }
    }
}
