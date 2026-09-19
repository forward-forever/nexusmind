package com.wude.nexusmind.agent.tool;

import com.wude.nexusmind.agent.application.AgentRunContext;
import com.wude.nexusmind.agent.application.AgentToolResultBudgeter;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.model.AgentSource;
import com.wude.nexusmind.agent.model.KnowledgeSearchToolResult;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.context.ContextBudgetExceededException;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.observability.NexusMindMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class KnowledgeSearchTool {

    public static final String TOOL_NAME = "search_knowledge_base";
    public static final String CONTEXT_KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String CONTEXT_AGENT_RUN = "agentRunContext";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    private final RetrievalServiceRegistry retrievalServiceRegistry;
    private final AgentProperties properties;
    private final AgentToolResultBudgeter resultBudgeter;
    private final NexusMindMetrics metrics;

    public KnowledgeSearchTool(RetrievalServiceRegistry retrievalServiceRegistry,
                               AgentProperties properties,
                               AgentToolResultBudgeter resultBudgeter) {
        this(retrievalServiceRegistry, properties, resultBudgeter, NexusMindMetrics.noop());
    }

    public KnowledgeSearchTool(RetrievalServiceRegistry retrievalServiceRegistry,
                               AgentProperties properties,
                               AgentToolResultBudgeter resultBudgeter,
                               NexusMindMetrics metrics) {
        this.retrievalServiceRegistry = retrievalServiceRegistry;
        this.properties = properties;
        this.resultBudgeter = resultBudgeter;
        this.metrics = metrics;
    }

    @Tool(
            name = TOOL_NAME,
            description = "Search the currently selected NexusMind knowledge base for information relevant "
                    + "to the user's question. Use this tool when the answer depends on documents stored "
                    + "in the knowledge base or when the user explicitly asks for information from the "
                    + "knowledge base. Search first when knowledge-base information is needed. Results "
                    + "contain source IDs that can later be passed to get_document_context when surrounding "
                    + "context is needed. Results may be truncated to the application context budget; "
                    + "repeating the same query normally will not reveal omitted content. Do not use it "
                    + "for casual conversation or questions that can be answered without the knowledge base.")
    public KnowledgeSearchToolResult search(
            @ToolParam(description = "A concise search query describing the needed knowledge") String query,
            ToolContext toolContext) {
        AgentRunContext runContext = requireRunContext(toolContext);
        long knowledgeBaseId = requireKnowledgeBaseId(toolContext);
        if (knowledgeBaseId != runContext.knowledgeBaseId()) {
            throw new IllegalStateException("Knowledge-base context does not match the agent run");
        }

        String normalizedQuery = requireQuery(query);
        String invocationId = UUID.randomUUID().toString();
        runContext.ensureTimeRemaining();
        runContext.publish(AgentStreamEvent.toolStart(
                runContext.runId(), runContext.sessionId(), invocationId, TOOL_NAME,
                Map.of("query", normalizedQuery)));
        long startedAt = System.nanoTime();
        Timer.Sample metricSample = metrics.start();

        try {
            RetrievalService retrievalService = retrievalServiceRegistry.get(
                    properties.knowledgeSearch().retriever());
            RetrievalResult result = retrievalService.retrieve(
                    knowledgeBaseId, normalizedQuery, properties.knowledgeSearch().topK());
            KnowledgeSearchToolResult toolResult = resultBudgeter.budgetSearch(
                    runContext, normalizedQuery, result.hits());
            List<AgentSource> sources = toolResult.items().stream()
                    .map(item -> new AgentSource(
                            item.sourceId(), item.chunkId(), item.documentId(), item.fileName(),
                            item.pageNo(), item.sectionTitle()))
                    .toList();
            long durationMs = elapsedMillis(startedAt);
            runContext.publish(AgentStreamEvent.toolResult(
                    runContext.runId(), runContext.sessionId(), invocationId, TOOL_NAME,
                    durationMs, toolResult.items().size(), sources));
            log.info("Agent tool completed: runId={}, toolName={}, knowledgeBaseId={}, retrieverType={}, "
                            + "resultCount={}, durationMs={}",
                    runContext.runId(), TOOL_NAME, knowledgeBaseId,
                    properties.knowledgeSearch().retriever(), toolResult.items().size(), durationMs);
            metrics.agentToolCompleted(metricSample, "native", "knowledge_search", "success");
            return toolResult;
        } catch (ContextBudgetExceededException budgetExceeded) {
            metrics.agentToolCompleted(metricSample, "native", "knowledge_search", "error");
            throw budgetExceeded;
        } catch (RuntimeException error) {
            metrics.agentToolCompleted(metricSample, "native", "knowledge_search", "error");
            runContext.publish(AgentStreamEvent.toolError(
                    runContext.runId(), runContext.sessionId(), invocationId, TOOL_NAME,
                    "KNOWLEDGE_SEARCH_FAILED", "知识库搜索失败，请稍后重试"));
            log.error("Agent tool failed: runId={}, toolName={}, knowledgeBaseId={}, retrieverType={}, "
                            + "durationMs={}, errorType={}",
                    runContext.runId(), TOOL_NAME, knowledgeBaseId,
                    properties.knowledgeSearch().retriever(), elapsedMillis(startedAt),
                    error.getClass().getSimpleName());
            throw error;
        }
    }

    private static AgentRunContext requireRunContext(ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalArgumentException("Agent tool context is required");
        }
        Object value = toolContext.getContext().get(CONTEXT_AGENT_RUN);
        if (!(value instanceof AgentRunContext runContext)) {
            throw new IllegalArgumentException("Agent run context is required");
        }
        return runContext;
    }

    private static long requireKnowledgeBaseId(ToolContext toolContext) {
        Object value = toolContext.getContext().get(CONTEXT_KNOWLEDGE_BASE_ID);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Knowledge-base context is required");
        }
        return number.longValue();
    }

    private static String requireQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Knowledge search query is required");
        }
        return query.trim();
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
