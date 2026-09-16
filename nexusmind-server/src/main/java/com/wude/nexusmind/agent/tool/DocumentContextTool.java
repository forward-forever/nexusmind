package com.wude.nexusmind.agent.tool;

import com.wude.nexusmind.agent.application.AgentRunContext;
import com.wude.nexusmind.agent.application.DocumentContextService;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.model.AgentSource;
import com.wude.nexusmind.agent.model.DocumentContextToolResult;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DocumentContextTool {

    public static final String TOOL_NAME = "get_document_context";

    private static final Logger log = LoggerFactory.getLogger(DocumentContextTool.class);

    private final DocumentContextService contextService;
    private final AgentProperties properties;

    public DocumentContextTool(DocumentContextService contextService,
                               AgentProperties properties) {
        this.contextService = contextService;
        this.properties = properties;
    }

    @Tool(
            name = TOOL_NAME,
            description = "Load nearby passages around a source that was returned earlier during this "
                    + "agent run. Use this when a search result is relevant but additional surrounding "
                    + "context is needed to answer accurately. The sourceId must come from a previous "
                    + "tool result. Do not invent source IDs.")
    public DocumentContextToolResult getContext(
            @ToolParam(description = "A source ID returned by an earlier tool result, for example S1")
            String sourceId,
            ToolContext toolContext) {
        AgentRunContext runContext = requireRunContext(toolContext);
        long knowledgeBaseId = requireKnowledgeBaseId(toolContext);
        if (knowledgeBaseId != runContext.knowledgeBaseId()) {
            throw new IllegalStateException("Knowledge-base context does not match the agent run");
        }

        String normalizedSourceId = requireSourceId(sourceId);
        String invocationId = UUID.randomUUID().toString();
        runContext.ensureTimeRemaining();
        runContext.publish(AgentStreamEvent.toolStart(
                runContext.runId(), runContext.sessionId(), invocationId, TOOL_NAME,
                Map.of("sourceId", normalizedSourceId)));
        long startedAt = System.nanoTime();

        try {
            AgentProperties.DocumentContext policy = properties.documentContext();
            DocumentContextToolResult result = contextService.load(
                    runContext, normalizedSourceId, policy.beforeChunks(), policy.afterChunks());
            List<AgentSource> sources = result.items().stream()
                    .map(item -> runContext.sourceRegistry().resolveSource(item.sourceId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Context source was not registered")))
                    .toList();
            long durationMs = elapsedMillis(startedAt);
            runContext.publish(AgentStreamEvent.toolResult(
                    runContext.runId(), runContext.sessionId(), invocationId, TOOL_NAME, durationMs,
                    result.items().size(), sources));
            log.info("Agent tool completed: runId={}, toolName={}, knowledgeBaseId={}, found={}, "
                            + "resultCount={}, durationMs={}",
                    runContext.runId(), TOOL_NAME, knowledgeBaseId, result.found(),
                    result.items().size(), durationMs);
            return result;
        } catch (RuntimeException error) {
            runContext.publish(AgentStreamEvent.toolError(
                    runContext.runId(), runContext.sessionId(), invocationId, TOOL_NAME,
                    "DOCUMENT_CONTEXT_FAILED", "文档上下文加载失败，请稍后重试"));
            log.error("Agent tool failed: runId={}, toolName={}, knowledgeBaseId={}, durationMs={}, "
                            + "errorType={}",
                    runContext.runId(), TOOL_NAME, knowledgeBaseId, elapsedMillis(startedAt),
                    error.getClass().getSimpleName(), error);
            throw error;
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

    private static long requireKnowledgeBaseId(ToolContext toolContext) {
        Object value = toolContext.getContext().get(KnowledgeSearchTool.CONTEXT_KNOWLEDGE_BASE_ID);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Knowledge-base context is required");
        }
        return number.longValue();
    }

    private static String requireSourceId(String sourceId) {
        if (sourceId == null || sourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Source ID is required");
        }
        return sourceId.trim();
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
