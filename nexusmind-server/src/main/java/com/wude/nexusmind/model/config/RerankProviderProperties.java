package com.wude.nexusmind.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nexusmind.ai.rerank")
public record RerankProviderProperties(
        boolean enabled,
        String model,
        String baseUrl,
        String apiKey,
        Duration timeout,
        String instruct) {

    public RerankProviderProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Rerank model is required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Rerank timeout must be positive");
        }
        if (instruct == null || instruct.isBlank()) {
            throw new IllegalArgumentException("Rerank instruct is required");
        }
        if (enabled && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalArgumentException("Rerank base-url is required when rerank is enabled");
        }
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("Rerank api-key is required when rerank is enabled");
        }
    }

    @Override
    public String toString() {
        return "RerankProviderProperties[enabled=" + enabled
                + ", model=" + model
                + ", baseUrl=" + baseUrl
                + ", apiKey=<redacted>"
                + ", timeout=" + timeout
                + ", instruct=" + instruct + "]";
    }
}
