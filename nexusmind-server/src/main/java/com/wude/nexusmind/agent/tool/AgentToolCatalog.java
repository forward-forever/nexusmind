package com.wude.nexusmind.agent.tool;

import com.wude.nexusmind.agent.mcp.McpToolRegistry;
import com.wude.nexusmind.context.NexusTokenEstimator;
import com.wude.nexusmind.context.TokenBudgetProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable startup snapshot of Native Java and explicitly allowed MCP tools. */
public final class AgentToolCatalog {

    private final List<ToolCallback> callbacks;
    private final int estimatedDefinitionTokens;

    public AgentToolCatalog(AgentToolSet nativeTools,
                            McpToolRegistry mcpTools,
                            ObjectMapper objectMapper,
                            NexusTokenEstimator estimator,
                            TokenBudgetProperties tokenBudgetProperties) {
        this(nativeTools.callbacks(), mcpTools.allowedCallbacks(), objectMapper, estimator,
                tokenBudgetProperties.toolDefinitionReserveTokens());
    }

    public AgentToolCatalog(List<ToolCallback> nativeTools,
                            List<ToolCallback> mcpTools,
                            ObjectMapper objectMapper,
                            NexusTokenEstimator estimator,
                            int definitionTokenReserve) {
        List<ToolCallback> combined = new ArrayList<>(nativeTools.size() + mcpTools.size());
        combined.addAll(nativeTools);
        combined.addAll(mcpTools);
        validateUniqueNames(combined);
        this.callbacks = List.copyOf(combined);
        this.estimatedDefinitionTokens = estimateDefinitions(
                objectMapper, estimator, combined);
        if (estimatedDefinitionTokens > definitionTokenReserve) {
            throw new IllegalStateException(
                    "MCP_TOOL_DEFINITION_BUDGET_EXCEEDED: reduce the MCP allowlist or increase "
                            + "nexusmind.ai.context.tool-definition-reserve-tokens");
        }
    }

    private AgentToolCatalog(List<ToolCallback> callbacks) {
        this.callbacks = List.copyOf(callbacks);
        this.estimatedDefinitionTokens = 0;
    }

    public static AgentToolCatalog nativeOnly(AgentToolSet nativeTools) {
        return new AgentToolCatalog(nativeTools.callbacks());
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    public int estimatedDefinitionTokens() {
        return estimatedDefinitionTokens;
    }

    private static void validateUniqueNames(List<ToolCallback> callbacks) {
        Set<String> names = new HashSet<>();
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (!names.add(name)) {
                throw new IllegalStateException("Agent tool name collision: " + name);
            }
        }
    }

    private static int estimateDefinitions(ObjectMapper objectMapper,
                                           NexusTokenEstimator estimator,
                                           List<ToolCallback> callbacks) {
        List<DefinitionForBudget> definitions = callbacks.stream()
                .map(ToolCallback::getToolDefinition)
                .map(DefinitionForBudget::from)
                .toList();
        try {
            return estimator.estimate(objectMapper.writeValueAsString(definitions));
        } catch (JacksonException error) {
            throw new IllegalStateException("Cannot serialize Agent tool definitions", error);
        }
    }

    private record DefinitionForBudget(String name, String description, String inputSchema) {
        private static DefinitionForBudget from(ToolDefinition definition) {
            return new DefinitionForBudget(
                    definition.name(), definition.description(), definition.inputSchema());
        }
    }
}
