package com.wude.nexusmind.model.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RagChatPropertiesTest {

    @Test
    void acceptsMvcTimeoutThatLeavesTimeToFlushAStreamError() {
        RagChatProperties properties = properties(Duration.ofSeconds(120), Duration.ofSeconds(150));

        assertThat(properties.mvcTimeout()).isGreaterThan(properties.streamTimeout());
    }

    @Test
    void rejectsMvcTimeoutEqualToOrShorterThanModelStreamTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(Duration.ofSeconds(120), Duration.ofSeconds(120)))
                .withMessageContaining("greater than stream-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(Duration.ofSeconds(120), Duration.ofSeconds(119)))
                .withMessageContaining("greater than stream-timeout");
    }

    private static RagChatProperties properties(Duration streamTimeout, Duration mvcTimeout) {
        return new RagChatProperties(
                "qwen3.5-flash", 0.2, 5, 10, 12_000, streamTimeout, mvcTimeout);
    }
}
