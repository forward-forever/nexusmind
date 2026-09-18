package com.wude.nexusmind.agent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpToolRegistryTest {

    @Test
    void disabledDoesNotTouchTheSpringMcpProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<McpToolResultBudgeter> budgeter = mock(ObjectProvider.class);

        McpToolRegistry registry = new McpToolRegistry(
                properties(false, List.of()), provider, budgeter);

        assertThat(registry.allowedCallbacks()).isEmpty();
        assertThat(registry.discovery()).isEqualTo(
                new McpDiscoveryResponse(false, 0, 0, List.of()));
        verifyNoInteractions(provider, budgeter);
    }

    @Test
    void exposesOnlyExplicitlyAllowlistedToolsFromStartupSnapshot() {
        ToolCallback a = callback("mcp_demo_a");
        ToolCallback b = callback("mcp_demo_b");
        ToolCallback c = callback("mcp_demo_c");
        SyncMcpToolCallbackProvider callbackProvider = mock(SyncMcpToolCallbackProvider.class);
        when(callbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{c, b, a});
        McpToolResultBudgeter budgeter = mock(McpToolResultBudgeter.class);

        McpToolRegistry registry = new McpToolRegistry(
                properties(true, List.of("mcp_demo_b")),
                provider(callbackProvider), provider(budgeter));

        assertThat(registry.allowedCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("mcp_demo_b");
        assertThat(registry.discovery().tools()).containsExactly(
                new McpDiscoveredTool("mcp_demo_a", false),
                new McpDiscoveredTool("mcp_demo_b", true),
                new McpDiscoveredTool("mcp_demo_c", false));
    }

    @Test
    void enabledWithEmptyAllowlistDiscoversButExposesNothing() {
        SyncMcpToolCallbackProvider callbackProvider = mock(SyncMcpToolCallbackProvider.class);
        when(callbackProvider.getToolCallbacks()).thenReturn(
                new ToolCallback[]{callback("mcp_demo_a")});

        McpToolRegistry registry = new McpToolRegistry(
                properties(true, List.of()), provider(callbackProvider),
                provider(mock(McpToolResultBudgeter.class)));

        assertThat(registry.allowedCallbacks()).isEmpty();
        assertThat(registry.discovery().discoveredCount()).isEqualTo(1);
        assertThat(registry.discovery().allowedCount()).isZero();
    }

    @Test
    void enabledDiscoveryFailureFailsFastWithoutLeakingConnectionDetails() {
        SyncMcpToolCallbackProvider callbackProvider = mock(SyncMcpToolCallbackProvider.class);
        when(callbackProvider.getToolCallbacks()).thenThrow(
                new IllegalStateException("https://secret.example/mcp?token=abc"));

        assertThatThrownBy(() -> new McpToolRegistry(
                properties(true, List.of()), provider(callbackProvider),
                provider(mock(McpToolResultBudgeter.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP initialization or tool discovery failed")
                .hasNoCause()
                .message().doesNotContain("secret", "token", "https://");
    }

    private static McpProperties properties(boolean enabled, List<String> allowed) {
        return new McpProperties(enabled, allowed, Duration.ofSeconds(8));
    }

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("description")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
