package com.wude.nexusmind.agent.mcp;

import com.wude.nexusmind.agent.application.AgentRunContext;
import com.wude.nexusmind.agent.application.AgentRunTokenBudget;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.context.ContextBudgetExceededException;
import com.wude.nexusmind.context.NexusTokenEstimator;
import com.wude.nexusmind.context.TokenTextTruncator;
import com.wude.nexusmind.observability.NexusMindMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class McpToolResultBudgeter {

    static final String TRUNCATION_NOTICE =
            "Result was truncated by NexusMind context policy.";

    private final ObjectMapper objectMapper;
    private final NexusTokenEstimator estimator;
    private final TokenTextTruncator truncator;
    private final AgentProperties agentProperties;
    private final NexusMindMetrics metrics;

    public McpToolResultBudgeter(ObjectMapper objectMapper,
                                 NexusTokenEstimator estimator,
                                 TokenTextTruncator truncator,
                                 AgentProperties agentProperties) {
        this(objectMapper, estimator, truncator, agentProperties, NexusMindMetrics.noop());
    }

    public McpToolResultBudgeter(ObjectMapper objectMapper,
                                 NexusTokenEstimator estimator,
                                 TokenTextTruncator truncator,
                                 AgentProperties agentProperties,
                                 NexusMindMetrics metrics) {
        this.objectMapper = objectMapper;
        this.estimator = estimator;
        this.truncator = truncator;
        this.agentProperties = agentProperties;
        this.metrics = metrics;
    }

    public String budget(AgentRunContext runContext, String rawResult) {
        String value = rawResult == null ? "" : rawResult;
        String result = runContext.tokenBudget().allocate(
                agentProperties.toolResult().maxTokensPerCall(),
                allowed -> plan(value, allowed));
        if (!result.equals(value)) {
            metrics.contextTruncated("tool");
        }
        return result;
    }

    NexusMindMetrics metrics() {
        return metrics;
    }

    private AgentRunTokenBudget.BudgetedValue<String> plan(String rawResult, int allowed) {
        int rawTokens = estimator.estimate(rawResult);
        if (rawTokens <= allowed) {
            return new AgentRunTokenBudget.BudgetedValue<>(rawResult, rawTokens);
        }

        TokenTextTruncator.TruncatedText shortened = truncator.truncateToFitRendered(
                        rawResult, allowed, this::truncatedEnvelope)
                .orElseThrow(() -> new ContextBudgetExceededException(
                        "MCP result envelope exceeds tool-result budget"));
        String envelope = truncatedEnvelope(shortened.text());
        int estimated = estimator.estimate(envelope);
        if (estimated > allowed) {
            throw new ContextBudgetExceededException(
                    "MCP result exceeds its serialized tool-result budget");
        }
        return new AgentRunTokenBudget.BudgetedValue<>(envelope, estimated);
    }

    private String truncatedEnvelope(String content) {
        try {
            return objectMapper.writeValueAsString(new TruncatedMcpResult(
                    true, TRUNCATION_NOTICE, content));
        } catch (JacksonException error) {
            throw new IllegalStateException("Cannot serialize MCP result envelope", error);
        }
    }

    private record TruncatedMcpResult(boolean truncated, String notice, String content) {
    }
}
