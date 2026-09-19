package com.wude.nexusmind.observability;

import com.wude.nexusmind.agent.mcp.McpProperties;
import com.wude.nexusmind.agent.mcp.McpToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpHealthIndicatorTest {

    @Test
    void disabledMcpIsHealthyWithoutRemoteProbe() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.wude.nexusmind.agent.mcp.McpToolResultBudgeter> budgeter =
                mock(ObjectProvider.class);
        McpToolRegistry registry = new McpToolRegistry(
                new McpProperties(false, List.of(), Duration.ofSeconds(8)), provider, budgeter);

        var health = new McpHealthIndicator(registry).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("enabled", false)
                .containsEntry("discoveredToolCount", 0);
    }
}
