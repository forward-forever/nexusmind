package com.wude.nexusmind.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NexusMindMetricsTest {

    @Test
    void recordsAgentToolRetrievalTaskAndErrorMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NexusMindMetrics metrics = new NexusMindMetrics(registry);

        Timer.Sample agent = metrics.start();
        metrics.agentModelTurn();
        metrics.agentToolCompleted(metrics.start(), "native", "knowledge_search", "success");
        metrics.agentCompleted(agent, "success");
        metrics.ragCompleted(metrics.start(), "DENSE", "success");
        metrics.retrievalCompleted(metrics.start(), "DENSE", "success");
        metrics.documentTaskCompleted(metrics.start(), "PROCESS", "success");
        metrics.contextBudgetExceeded("agent");
        metrics.contextTruncated("rag");
        metrics.sessionBusy();
        metrics.sessionLeaseLost();
        metrics.invalidCitations(2);

        assertThat(registry.get("nexusmind.agent.runs").tag("outcome", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("nexusmind.agent.model.turns").counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.agent.tool.calls").tag("toolType", "native")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.retrieval.requests").tag("retriever", "dense")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.rag.requests").tag("retriever", "dense")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.document.task.completed").tag("taskType", "process")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.context.budget.exceeded").tag("type", "agent")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.context.truncations").tag("type", "rag")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.agent.session.busy").counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.agent.session.lease_lost").counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.agent.citation.invalid").counter().count()).isEqualTo(2);
    }

    @Test
    void recordsProviderRetriesAndMcpWithoutDynamicToolTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NexusMindMetrics metrics = new NexusMindMetrics(registry);

        metrics.providerRetry("alibaba", "chat", "HTTP_429");
        metrics.providerCompleted(metrics.start(), "alibaba", "chat", "error");
        metrics.mcpCompleted(metrics.start(), "success");

        assertThat(registry.get("nexusmind.ai.provider.retries")
                .tag("failureCategory", "http_429").counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.ai.provider.errors").counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.mcp.calls")
                .tag("transport", "streamable_http").counter().count()).isEqualTo(1);

        Set<String> forbidden = Set.of("runId", "sessionId", "taskId", "documentId",
                "knowledgeBaseId", "query", "toolName", "url");
        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .filter(forbidden::contains)
                .toList()).isEmpty();
        assertThat(registry.getMeters()).allMatch(meter ->
                meter.getId().getName().startsWith("nexusmind."));
    }
}
