package com.wude.nexusmind.agent.tool;

import com.wude.nexusmind.support.TestTokenSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolCatalogTest {

    @Test
    void emptyMcpSnapshotLeavesTheTwoNativeToolsOnly() {
        AgentToolCatalog catalog = new AgentToolCatalog(
                List.of(callback("search_knowledge_base", "native"),
                        callback("get_document_context", "native")),
                List.of(), new ObjectMapper(), TestTokenSupport.estimator(), 2_000);

        assertThat(catalog.callbacks())
                .extracting(value -> value.getToolDefinition().name())
                .containsExactly("search_knowledge_base", "get_document_context");
    }

    @Test
    void combinesNativeAndMcpToolsWithoutChangingOrder() {
        AgentToolCatalog catalog = new AgentToolCatalog(
                List.of(callback("search_knowledge_base", "native"),
                        callback("get_document_context", "native")),
                List.of(callback("mcp_demo_echo", "external")),
                new ObjectMapper(), TestTokenSupport.estimator(), 2_000);

        assertThat(catalog.callbacks())
                .extracting(value -> value.getToolDefinition().name())
                .containsExactly("search_knowledge_base", "get_document_context", "mcp_demo_echo");
        assertThat(catalog.estimatedDefinitionTokens()).isPositive().isLessThanOrEqualTo(2_000);
    }

    @Test
    void failsFastOnNativeMcpNameCollision() {
        assertThatThrownBy(() -> new AgentToolCatalog(
                List.of(callback("mcp_demo_echo", "native")),
                List.of(callback("mcp_demo_echo", "external")),
                new ObjectMapper(), TestTokenSupport.estimator(), 2_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collision");
    }

    @Test
    void failsFastWhenVisibleDefinitionsExceedConfiguredReserve() {
        assertThatThrownBy(() -> new AgentToolCatalog(
                List.of(callback("native", "x".repeat(300))),
                List.of(callback("mcp_demo_echo", "y".repeat(300))),
                new ObjectMapper(), TestTokenSupport.estimator(), 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_TOOL_DEFINITION_BUDGET_EXCEEDED");
    }

    private static ToolCallback callback(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description)
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}
