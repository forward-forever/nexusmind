package com.wude.nexusmind.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Locale;

/** Low-cardinality metrics facade for NexusMind application workflows. */
public final class NexusMindMetrics {

    private final MeterRegistry registry;

    public NexusMindMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static NexusMindMetrics noop() {
        return new NexusMindMetrics(new SimpleMeterRegistry());
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void ragCompleted(Timer.Sample sample, String retriever, String outcome) {
        counter("nexusmind.rag.requests", "retriever", normalized(retriever), "outcome", outcome).increment();
        stop(sample, "nexusmind.rag.duration", "retriever", normalized(retriever), "outcome", outcome);
        if (!"success".equals(outcome)) {
            counter("nexusmind.rag.errors", "retriever", normalized(retriever), "outcome", outcome).increment();
        }
    }

    public void retrievalCompleted(Timer.Sample sample, String retriever, String outcome) {
        counter("nexusmind.retrieval.requests", "retriever", normalized(retriever), "outcome", outcome).increment();
        stop(sample, "nexusmind.retrieval.duration", "retriever", normalized(retriever), "outcome", outcome);
        if (!"success".equals(outcome)) {
            counter("nexusmind.retrieval.errors", "retriever", normalized(retriever), "outcome", outcome).increment();
        }
    }

    public void retrievalRoute(Timer.Sample sample, String route, String outcome) {
        stop(sample, "nexusmind.retrieval.route.duration", "route", normalized(route), "outcome", outcome);
    }

    public void providerCompleted(Timer.Sample sample, String provider, String operation, String outcome) {
        String operationTag = providerOperation(operation);
        counter("nexusmind.ai.provider.calls", "provider", normalized(provider),
                "operation", operationTag, "outcome", outcome).increment();
        stop(sample, "nexusmind.ai.provider.duration", "provider", normalized(provider),
                "operation", operationTag, "outcome", outcome);
        if (!"success".equals(outcome)) {
            counter("nexusmind.ai.provider.errors", "provider", normalized(provider),
                    "operation", operationTag, "outcome", outcome).increment();
        }
    }

    public void providerRetry(String provider, String operation, String failureCategory) {
        counter("nexusmind.ai.provider.retries", "provider", normalized(provider),
                "operation", providerOperation(operation),
                "failureCategory", normalized(failureCategory)).increment();
    }

    public void agentModelTurn() {
        counter("nexusmind.agent.model.turns").increment();
    }

    public void agentCompleted(Timer.Sample sample, String outcome) {
        counter("nexusmind.agent.runs", "outcome", outcome).increment();
        stop(sample, "nexusmind.agent.duration", "outcome", outcome);
        if (!"success".equals(outcome)) {
            counter("nexusmind.agent.errors", "outcome", outcome).increment();
        }
    }

    public void agentToolCompleted(Timer.Sample sample, String toolType, String operation, String outcome) {
        counter("nexusmind.agent.tool.calls", "toolType", toolType, "operation", operation,
                "outcome", outcome).increment();
        stop(sample, "nexusmind.agent.tool.duration", "toolType", toolType,
                "operation", operation, "outcome", outcome);
        if (!"success".equals(outcome)) {
            counter("nexusmind.agent.tool.errors", "toolType", toolType,
                    "operation", operation, "outcome", outcome).increment();
        }
    }

    public void mcpCompleted(Timer.Sample sample, String outcome) {
        counter("nexusmind.mcp.calls", "transport", "streamable_http", "outcome", outcome).increment();
        stop(sample, "nexusmind.mcp.duration", "transport", "streamable_http", "outcome", outcome);
        if (!"success".equals(outcome)) {
            counter("nexusmind.mcp.errors", "transport", "streamable_http", "outcome", outcome).increment();
        }
    }

    public void documentTaskEnqueued(String taskType) {
        counter("nexusmind.document.task.enqueued", "taskType", normalized(taskType)).increment();
    }

    public void documentTaskCompleted(Timer.Sample sample, String taskType, String outcome) {
        String type = normalized(taskType);
        counter("success".equals(outcome)
                ? "nexusmind.document.task.completed" : "nexusmind.document.task.failed",
                "taskType", type).increment();
        stop(sample, "nexusmind.document.task.duration", "taskType", type, "outcome", outcome);
    }

    public void documentTaskRecovered(String taskType, String outcome) {
        counter("nexusmind.document.task.recovered", "taskType", normalized(taskType),
                "outcome", normalized(outcome)).increment();
    }

    public void contextBudgetExceeded(String type) {
        counter("nexusmind.context.budget.exceeded", "type", type).increment();
    }

    public void contextTruncated(String type) {
        counter("nexusmind.context.truncations", "type", type).increment();
    }

    public void sessionBusy() {
        counter("nexusmind.agent.session.busy").increment();
    }

    public void sessionLeaseLost() {
        counter("nexusmind.agent.session.lease_lost").increment();
    }

    public void invalidCitations(int count) {
        if (count > 0) {
            counter("nexusmind.agent.citation.invalid").increment(count);
        }
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    private void stop(Timer.Sample sample, String name, String... tags) {
        sample.stop(Timer.builder(name).tags(tags).register(registry));
    }

    private static String normalized(String value) {
        return value == null ? "unknown" : value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String providerOperation(String operation) {
        String normalized = normalized(operation);
        if (normalized.contains("embedding")) {
            return "embedding";
        }
        if (normalized.contains("rerank")) {
            return "rerank";
        }
        if (normalized.contains("chat") || normalized.contains("model_turn")
                || normalized.equals("turn")) {
            return "chat";
        }
        return normalized;
    }
}
