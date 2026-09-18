package com.wude.nexusmind.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpDiscoveryControllerTest {

    @Test
    void disabledResponseIsSafeAndEmpty() {
        McpToolRegistry registry = mock(McpToolRegistry.class);
        when(registry.discovery()).thenReturn(
                new McpDiscoveryResponse(false, 0, 0, List.of()));

        McpDiscoveryResponse response = new McpDiscoveryController(registry).tools();

        assertThat(response.enabled()).isFalse();
        assertThat(response.tools()).isEmpty();
        assertThat(response.toString()).doesNotContain("url", "token", "schema", "secret");
    }

    @Test
    void enabledResponseContainsOnlySafeDiscoveryMetadata() {
        McpToolRegistry registry = mock(McpToolRegistry.class);
        when(registry.discovery()).thenReturn(new McpDiscoveryResponse(
                true, 2, 1, List.of(
                new McpDiscoveredTool("mcp_demo_a", false),
                new McpDiscoveredTool("mcp_demo_b", true))));

        McpDiscoveryResponse response = new McpDiscoveryController(registry).tools();

        assertThat(response.enabled()).isTrue();
        assertThat(response.discoveredCount()).isEqualTo(2);
        assertThat(response.allowedCount()).isEqualTo(1);
    }
}
