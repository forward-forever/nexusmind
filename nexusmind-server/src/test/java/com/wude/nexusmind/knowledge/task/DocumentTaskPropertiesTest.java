package com.wude.nexusmind.knowledge.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTaskPropertiesTest {

    @Test
    void acceptsSafeDefaults() {
        assertThatCode(new DocumentTaskProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveConcurrency() {
        DocumentTaskProperties properties = new DocumentTaskProperties();
        properties.setWorkerConcurrency(0);
        assertThatThrownBy(properties::validate).hasMessageContaining("worker-concurrency");
    }

    @Test
    void requiresStaleTimeoutGreaterThanHeartbeat() {
        DocumentTaskProperties properties = new DocumentTaskProperties();
        properties.setHeartbeatInterval(Duration.ofSeconds(30));
        properties.setStaleAfter(Duration.ofSeconds(10));
        assertThatThrownBy(properties::validate).hasMessageContaining("stale-after");
    }

    @Test
    void rejectsNegativeRecoveryLimit() {
        DocumentTaskProperties properties = new DocumentTaskProperties();
        properties.setMaxStaleRecoveries(-1);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-stale-recoveries");
    }
}
