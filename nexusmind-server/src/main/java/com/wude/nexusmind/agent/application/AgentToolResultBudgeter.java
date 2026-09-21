package com.wude.nexusmind.agent.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.model.AgentSource;
import com.wude.nexusmind.agent.model.DocumentContextItem;
import com.wude.nexusmind.agent.model.DocumentContextToolResult;
import com.wude.nexusmind.agent.model.KnowledgeSearchItem;
import com.wude.nexusmind.agent.model.KnowledgeSearchToolResult;
import com.wude.nexusmind.context.ContextBudgetExceededException;
import com.wude.nexusmind.context.NexusTokenEstimator;
import com.wude.nexusmind.context.TokenTextTruncator;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.observability.NexusMindMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AgentToolResultBudgeter {

    private static final int CONSERVATIVE_ESTIMATE_PLACEHOLDER = 999_999;

    private final ObjectMapper objectMapper;
    private final NexusTokenEstimator estimator;
    private final TokenTextTruncator truncator;
    private final AgentProperties properties;
    private final NexusMindMetrics metrics;

    public AgentToolResultBudgeter(ObjectMapper objectMapper,
                                   NexusTokenEstimator estimator,
                                   TokenTextTruncator truncator,
                                   AgentProperties properties) {
        this(objectMapper, estimator, truncator, properties, NexusMindMetrics.noop());
    }

    public AgentToolResultBudgeter(ObjectMapper objectMapper,
                                   NexusTokenEstimator estimator,
                                   TokenTextTruncator truncator,
                                   AgentProperties properties,
                                   NexusMindMetrics metrics) {
        this.objectMapper = objectMapper;
        this.estimator = estimator;
        this.truncator = truncator;
        this.properties = properties;
        this.metrics = metrics;
    }

    public KnowledgeSearchToolResult budgetSearch(AgentRunContext runContext,
                                                   String query,
                                                   List<RetrievalHit> rankedHits) {
        KnowledgeSearchToolResult result = runContext.tokenBudget().allocate(
                properties.toolResult().maxTokensPerCall(),
                allowed -> planSearch(runContext, query, rankedHits, allowed));
        if (result.truncated()) {
            metrics.contextTruncated("tool");
        }
        return result;
    }

    public DocumentContextToolResult budgetEmptyContext(AgentRunContext runContext,
                                                         DocumentContextToolResult result) {
        DocumentContextToolResult budgeted = runContext.tokenBudget().allocate(
                properties.toolResult().maxTokensPerCall(),
                allowed -> {
                    DocumentContextToolResult finalized = finalizeContext(
                            result.found(), result.requestedSourceId(), result.reason(),
                            result.items(), result.truncated(), result.omittedItemCount());
                    int estimated = estimateJson(finalized);
                    if (estimated > allowed) {
                        throw new ContextBudgetExceededException(
                                "Agent context result metadata exceeds tool budget");
                    }
                    return new AgentRunTokenBudget.BudgetedValue<>(finalized, estimated);
                });
        if (budgeted.truncated()) {
            metrics.contextTruncated("tool");
        }
        return budgeted;
    }

    public DocumentContextToolResult budgetDocumentContext(AgentRunContext runContext,
                                                            String requestedSourceId,
                                                            KnowledgeDocument document,
                                                            List<KnowledgeChunk> chunks,
                                                            long targetChunkId) {
        DocumentContextToolResult result = runContext.tokenBudget().allocate(
                properties.toolResult().maxTokensPerCall(),
                allowed -> planContext(
                        runContext, requestedSourceId, document, chunks, targetChunkId, allowed));
        if (result.truncated()) {
            metrics.contextTruncated("tool");
        }
        return result;
    }

    private AgentRunTokenBudget.BudgetedValue<KnowledgeSearchToolResult> planSearch(
            AgentRunContext runContext,
            String query,
            List<RetrievalHit> rankedHits,
            int allowed) {
        if (rankedHits.isEmpty()) {
            KnowledgeSearchToolResult empty = finalizeSearch(false, query, List.of(), false, 0);
            return requireFits(empty, allowed);
        }

        List<KnowledgeSearchItem> included = new ArrayList<>();
        boolean contentTruncated = false;
        for (RetrievalHit hit : rankedHits) {
            String previewSourceId = runContext.sourceRegistry().previewSourceId(hit.chunkId());
            KnowledgeSearchItem candidate = searchItem(previewSourceId, hit, hit.content());
            List<KnowledgeSearchItem> prospective = append(included, candidate);
            int omitted = rankedHits.size() - prospective.size();
            KnowledgeSearchToolResult conservative = new KnowledgeSearchToolResult(
                    true, query, prospective, omitted > 0, omitted,
                    CONSERVATIVE_ESTIMATE_PLACEHOLDER);
            if (estimateJson(conservative) <= allowed) {
                AgentSource registered = runContext.sourceRegistry().register(hit);
                requireExpectedSourceId(previewSourceId, registered.sourceId());
                included.add(searchItem(registered.sourceId(), hit, hit.content()));
                continue;
            }

            // 如果没有包含任何项目，则尝试截断第一个项目
            if (included.isEmpty()) {
                int omittedAfterFirst = rankedHits.size() - 1;
                TokenTextTruncator.TruncatedText shortened = truncator.truncateToFitRendered(
                                hit.content(), allowed,
                                content -> json(new KnowledgeSearchToolResult(
                                        true, query,
                                        List.of(searchItem(previewSourceId, hit, content)),
                                        true, omittedAfterFirst,
                                        CONSERVATIVE_ESTIMATE_PLACEHOLDER)))
                        .orElseThrow(() -> new ContextBudgetExceededException(
                                "Search result metadata exceeds tool-result budget"));
                AgentSource registered = runContext.sourceRegistry().register(hit);
                requireExpectedSourceId(previewSourceId, registered.sourceId());
                included.add(searchItem(registered.sourceId(), hit, shortened.text()));
                contentTruncated = true;
            }
            break;
        }

        int omitted = rankedHits.size() - included.size();
        KnowledgeSearchToolResult result = finalizeSearch(
                !included.isEmpty(), query, included,
                contentTruncated || omitted > 0, omitted);
        return requireFits(result, allowed);
    }

    private AgentRunTokenBudget.BudgetedValue<DocumentContextToolResult> planContext(
            AgentRunContext runContext,
            String requestedSourceId,
            KnowledgeDocument document,
            List<KnowledgeChunk> chunks,
            long targetChunkId,
            int allowed) {
        KnowledgeChunk target = chunks.stream()
                .filter(chunk -> chunk.getId() == targetChunkId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Document context query did not return the target chunk"));
        List<SelectedContextItem> selected = new ArrayList<>();

        String targetSourceId = runContext.sourceRegistry().previewSourceId(targetChunkId);
        DocumentContextItem fullTarget = contextItem(targetSourceId, document, target, true,
                target.getContent());
        DocumentContextToolResult targetOnly = new DocumentContextToolResult(
                true, requestedSourceId, null, List.of(fullTarget), chunks.size() > 1,
                chunks.size() - 1, CONSERVATIVE_ESTIMATE_PLACEHOLDER);
        boolean targetTruncated = false;
        DocumentContextItem selectedTarget;
        if (estimateJson(targetOnly) <= allowed) {
            selectedTarget = fullTarget;
        } else {
            TokenTextTruncator.TruncatedText shortened = truncator.truncateToFitRendered(
                            target.getContent(), allowed,
                            content -> json(new DocumentContextToolResult(
                                    true, requestedSourceId, null,
                                    List.of(contextItem(
                                            targetSourceId, document, target, true, content)),
                                    true, chunks.size() - 1,
                                    CONSERVATIVE_ESTIMATE_PLACEHOLDER)))
                    .orElseThrow(() -> new ContextBudgetExceededException(
                            "Target context metadata exceeds tool-result budget"));
            selectedTarget = contextItem(
                    targetSourceId, document, target, true, shortened.text());
            targetTruncated = true;
        }
        AgentSource registeredTarget = register(runContext, document, target);
        requireExpectedSourceId(targetSourceId, registeredTarget.sourceId());
        selected.add(new SelectedContextItem(target.getChunkIndex(), selectedTarget));

        List<KnowledgeChunk> neighbors = chunks.stream()
                .filter(chunk -> chunk.getId() != targetChunkId)
                .sorted(Comparator
                        .comparingInt((KnowledgeChunk chunk) -> Math.abs(
                                chunk.getChunkIndex() - target.getChunkIndex()))
                        .thenComparingInt(KnowledgeChunk::getChunkIndex))
                .toList();
        for (KnowledgeChunk neighbor : neighbors) {
            String previewSourceId = runContext.sourceRegistry().previewSourceId(neighbor.getId());
            DocumentContextItem item = contextItem(
                    previewSourceId, document, neighbor, false, neighbor.getContent());
            List<SelectedContextItem> prospective = new ArrayList<>(selected);
            prospective.add(new SelectedContextItem(neighbor.getChunkIndex(), item));
            List<DocumentContextItem> ordered = orderedItems(prospective);
            int omitted = chunks.size() - ordered.size();
            DocumentContextToolResult conservative = new DocumentContextToolResult(
                    true, requestedSourceId, null, ordered,
                    targetTruncated || omitted > 0, omitted,
                    CONSERVATIVE_ESTIMATE_PLACEHOLDER);
            if (estimateJson(conservative) > allowed) {
                break;
            }
            AgentSource registered = register(runContext, document, neighbor);
            requireExpectedSourceId(previewSourceId, registered.sourceId());
            selected.add(new SelectedContextItem(neighbor.getChunkIndex(),
                    contextItem(registered.sourceId(), document, neighbor, false,
                            neighbor.getContent())));
        }

        List<DocumentContextItem> items = orderedItems(selected);
        int omitted = chunks.size() - items.size();
        DocumentContextToolResult result = finalizeContext(
                true, requestedSourceId, null, items,
                targetTruncated || omitted > 0, omitted);
        return requireFits(result, allowed);
    }

    private KnowledgeSearchToolResult finalizeSearch(boolean found,
                                                     String query,
                                                     List<KnowledgeSearchItem> items,
                                                     boolean truncated,
                                                     int omitted) {
        int estimated = 0;
        KnowledgeSearchToolResult result = null;
        // 收敛估计值
        for (int iteration = 0; iteration < 4; iteration++) {
            result = new KnowledgeSearchToolResult(
                    found, query, items, truncated, omitted, estimated);
            int next = estimateJson(result);
            if (next == estimated) {
                return result;
            }
            estimated = next;
        }
        return new KnowledgeSearchToolResult(found, query, items, truncated, omitted, estimated);
    }

    private DocumentContextToolResult finalizeContext(boolean found,
                                                       String sourceId,
                                                       String reason,
                                                       List<DocumentContextItem> items,
                                                       boolean truncated,
                                                       int omitted) {
        int estimated = 0;
        DocumentContextToolResult result = null;
        for (int iteration = 0; iteration < 4; iteration++) {
            result = new DocumentContextToolResult(
                    found, sourceId, reason, items, truncated, omitted, estimated);
            int next = estimateJson(result);
            if (next == estimated) {
                return result;
            }
            estimated = next;
        }
        return new DocumentContextToolResult(
                found, sourceId, reason, items, truncated, omitted, estimated);
    }

    private <T> AgentRunTokenBudget.BudgetedValue<T> requireFits(T value, int allowed) {
        int estimated = estimateJson(value);
        if (estimated > allowed) {
            throw new ContextBudgetExceededException("Tool result exceeds serialized token budget");
        }
        return new AgentRunTokenBudget.BudgetedValue<>(value, estimated);
    }

    private int estimateJson(Object value) {
        return estimator.estimate(json(value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not serialize tool result for token estimation", error);
        }
    }

    private static KnowledgeSearchItem searchItem(String sourceId,
                                                   RetrievalHit hit,
                                                   String content) {
        return new KnowledgeSearchItem(
                sourceId, hit.chunkId(), hit.documentId(), hit.fileName(), hit.pageNo(),
                hit.sectionTitle(), content);
    }

    private static DocumentContextItem contextItem(String sourceId,
                                                   KnowledgeDocument document,
                                                   KnowledgeChunk chunk,
                                                   boolean target,
                                                   String content) {
        return new DocumentContextItem(
                sourceId, document.getOriginalFileName(), chunk.getPageNo(),
                chunk.getSectionTitle(), content, target);
    }

    private static AgentSource register(AgentRunContext runContext,
                                        KnowledgeDocument document,
                                        KnowledgeChunk chunk) {
        return runContext.sourceRegistry().register(
                chunk.getId(), chunk.getDocumentId(), document.getOriginalFileName(),
                chunk.getPageNo(), chunk.getSectionTitle());
    }

    private static List<DocumentContextItem> orderedItems(List<SelectedContextItem> selected) {
        return selected.stream()
                .sorted(Comparator.comparingInt(SelectedContextItem::chunkIndex))
                .map(SelectedContextItem::item)
                .toList();
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(value);
        return result;
    }

    private static void requireExpectedSourceId(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Concurrent source registration changed source identity");
        }
    }

    private record SelectedContextItem(int chunkIndex, DocumentContextItem item) {
    }
}
