package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.stream.AgentToolEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentRunContext {

    private final String runId;
    private final String sessionId;
    private final long knowledgeBaseId;
    private final int historyMessageCount;
    private final Instant startedAt;
    private final Instant deadline;
    private final Clock clock;
    private final AtomicInteger toolCallCount = new AtomicInteger();
    private final AtomicInteger modelTurnCount = new AtomicInteger();
    private final AgentSourceRegistry sourceRegistry = new AgentSourceRegistry();
    private final AgentToolEventPublisher eventPublisher;
    private final AgentRunTokenBudget tokenBudget;

    public AgentRunContext(long knowledgeBaseId,
                           Duration maxDuration,
                           Clock clock,
                           AgentToolEventPublisher eventPublisher) {
        this(UUID.randomUUID().toString(), UUID.randomUUID().toString(), knowledgeBaseId,
                0, maxDuration, clock, eventPublisher, 12_000);
    }

    public AgentRunContext(String sessionId,
                           long knowledgeBaseId,
                           Duration maxDuration,
                           Clock clock,
                           AgentToolEventPublisher eventPublisher) {
        this(UUID.randomUUID().toString(), sessionId, knowledgeBaseId,
                0, maxDuration, clock, eventPublisher, 12_000);
    }

    public AgentRunContext(String sessionId,
                           long knowledgeBaseId,
                           int historyMessageCount,
                           Duration maxDuration,
                           Clock clock,
                           AgentToolEventPublisher eventPublisher) {
        this(UUID.randomUUID().toString(), sessionId, knowledgeBaseId,
                historyMessageCount, maxDuration, clock, eventPublisher, 12_000);
    }

    AgentRunContext(String runId,
                    String sessionId,
                    long knowledgeBaseId,
                    int historyMessageCount,
                    Duration maxDuration,
                    Clock clock,
                    AgentToolEventPublisher eventPublisher,
                    int toolResultMaxTokensPerRun) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.historyMessageCount = historyMessageCount;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.startedAt = clock.instant();
        this.deadline = startedAt.plus(maxDuration);
        this.tokenBudget = new AgentRunTokenBudget(toolResultMaxTokensPerRun);
    }

    /**
     * Throws an exception if the deadline has been exceeded.
     */
    public void ensureTimeRemaining() {
        if (!clock.instant().isBefore(deadline)) {
            throw AgentExecutionException.timeout();
        }
    }


    public Duration remaining() {
        ensureTimeRemaining();
        return Duration.between(clock.instant(), deadline);
    }

    public void reserveToolCalls(int requested, int limit) {
        if (requested < 1) {
            throw new IllegalArgumentException("Requested tool-call count must be positive");
        }
        while (true) {
            int current = toolCallCount.get();
            if (current + requested > limit) {
                throw AgentExecutionException.toolLimit();
            }
            if (toolCallCount.compareAndSet(current, current + requested)) {
                return;
            }
        }
    }

    public int incrementModelTurn() {
        return modelTurnCount.incrementAndGet();
    }

    public long elapsedMillis() {
        return Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
    }

    public String runId() {
        return runId;
    }

    public String sessionId() {
        return sessionId;
    }

    public long knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public int historyMessageCount() {
        return historyMessageCount;
    }

    public int toolCallCount() {
        return toolCallCount.get();
    }

    public int modelTurnCount() {
        return modelTurnCount.get();
    }

    public AgentSourceRegistry sourceRegistry() {
        return sourceRegistry;
    }

    public AgentRunTokenBudget tokenBudget() {
        return tokenBudget;
    }

    public void publish(AgentStreamEvent event) {
        eventPublisher.publish(event);
    }
}
