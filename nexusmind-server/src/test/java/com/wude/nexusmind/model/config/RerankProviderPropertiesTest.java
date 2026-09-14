package com.wude.nexusmind.model.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankProviderPropertiesTest {

    @Test
    void requiresProviderLocationAndCredentialOnlyWhenEnabled() {
        assertThat(new RerankProviderProperties(
                false, "qwen3.7-text-rerank", "", "", Duration.ofSeconds(3), instruct()))
                .extracting(RerankProviderProperties::enabled)
                .isEqualTo(false);
        assertThatThrownBy(() -> new RerankProviderProperties(
                true, "qwen3.7-text-rerank", "", "fake", Duration.ofSeconds(3), instruct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base-url");
        assertThatThrownBy(() -> new RerankProviderProperties(
                true, "qwen3.7-text-rerank", "https://example/api/v1", "",
                Duration.ofSeconds(3), instruct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void requiresIndependentPositiveTimeoutAndRedactsCredentialFromToString() {
        assertThatThrownBy(() -> new RerankProviderProperties(
                false, "qwen3.7-text-rerank", "", "", Duration.ZERO, instruct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        RerankProviderProperties properties = new RerankProviderProperties(
                true, "qwen3.7-text-rerank", "https://example/api/v1", "sensitive-value",
                Duration.ofSeconds(3), instruct());
        assertThat(properties.toString())
                .contains("apiKey=<redacted>")
                .doesNotContain("sensitive-value");
    }

    private static String instruct() {
        return "Given a web search query, retrieve relevant passages that answer the query.";
    }
}
