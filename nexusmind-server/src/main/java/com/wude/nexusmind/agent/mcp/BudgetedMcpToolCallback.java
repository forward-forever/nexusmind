package com.wude.nexusmind.agent.mcp;

import com.wude.nexusmind.agent.application.AgentRunContext;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.context.ContextBudgetExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import com.wude.nexusmind.observability.NexusMindMetrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BudgetedMcpToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BudgetedMcpToolCallback.class);

    private final ToolCallback delegate;
    private final McpToolResultBudgeter resultBudgeter;
    private final McpProperties properties;
    private final NexusMindMetrics metrics;

    public BudgetedMcpToolCallback(ToolCallback delegate,
                                   McpToolResultBudgeter resultBudgeter,
                                   McpProperties properties) {
        this.delegate = delegate;
        this.resultBudgeter = resultBudgeter;
        this.properties = properties;
        this.metrics = resultBudgeter.metrics();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        throw new IllegalStateException("MCP agent tool context is required");
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        AgentRunContext runContext = requireRunContext(toolContext);
        runContext.ensureMinimumTimeRemaining(properties.requestTimeout());
        String toolName = getToolDefinition().name();
        String invocationId = UUID.randomUUID().toString();
        runContext.publish(AgentStreamEvent.toolStart(
                runContext.runId(), runContext.sessionId(), invocationId, toolName, Map.of()));
        long startedAt = System.nanoTime();
        Timer.Sample agentMetricSample = metrics.start();
        Timer.Sample mcpMetricSample = metrics.start();
        try {
            String rawResult = delegate.call(toolInput, toolContext);
            String result = resultBudgeter.budget(runContext, rawResult);
            long durationMs = elapsedMillis(startedAt);
            runContext.publish(AgentStreamEvent.toolResult(
                    runContext.runId(), runContext.sessionId(), invocationId, toolName,
                    durationMs, result.isBlank() ? 0 : 1, List.of()));
            log.info("MCP agent tool completed: runId={}, toolName={}, durationMs={}",
                    runContext.runId(), toolName, durationMs);
            metrics.agentToolCompleted(agentMetricSample, "mcp", "mcp", "success");
            metrics.mcpCompleted(mcpMetricSample, "success");
            return result;
        } catch (ContextBudgetExceededException budgetExceeded) {
            metrics.agentToolCompleted(agentMetricSample, "mcp", "mcp", "error");
            metrics.mcpCompleted(mcpMetricSample, "error");
            throw budgetExceeded;
        } catch (RuntimeException error) {
            long durationMs = elapsedMillis(startedAt);
            runContext.publish(AgentStreamEvent.toolError(
                    runContext.runId(), runContext.sessionId(), invocationId, toolName,
                    "MCP_TOOL_ERROR", "MCP tool execution failed"));
            log.error("MCP agent tool failed: runId={}, toolName={}, durationMs={}, errorType={}",
                    runContext.runId(), toolName, durationMs,
                    error.getClass().getSimpleName());
            metrics.agentToolCompleted(agentMetricSample, "mcp", "mcp", "error");
            metrics.mcpCompleted(mcpMetricSample, "error");
            throw new McpToolExecutionException(toolName);
        }
    }

    private static AgentRunContext requireRunContext(ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalArgumentException("Agent tool context is required");
        }
        Object value = toolContext.getContext().get(KnowledgeSearchTool.CONTEXT_AGENT_RUN);
        if (!(value instanceof AgentRunContext runContext)) {
            throw new IllegalArgumentException("Agent run context is required");
        }
        return runContext;
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
