package com.wude.nexusmind.agent.evaluation;

import com.wude.nexusmind.agent.application.AgentChatService;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AgentChatBehaviorExecutor implements AgentBehaviorExecutor {

    private final AgentChatService agentChatService;
    private final Duration evaluationTimeout;

    public AgentChatBehaviorExecutor(AgentChatService agentChatService,
                                     AgentProperties properties) {
        this.agentChatService = agentChatService;
        this.evaluationTimeout = properties.maxDuration().plusSeconds(5);
    }

    @Override
    public AgentBehaviorRunResult execute(long knowledgeBaseId,
                                          String sessionId,
                                          String message) {
        long startedAt = System.nanoTime();
        try {
            List<AgentStreamEvent> events = agentChatService.chatForEvaluation(
                            knowledgeBaseId, sessionId, message)
                    .collectList()
                    .block(evaluationTimeout);
            if (events == null) {
                throw new IllegalStateException("Agent behavior run returned no events");
            }
            return fromEvents(events, elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            return new AgentBehaviorRunResult(
                    sessionId, null, false, List.of(), 0, 0, elapsedMillis(startedAt),
                    "AGENT_EXECUTION_EXCEPTION", exception.getMessage());
        }
    }

    private static AgentBehaviorRunResult fromEvents(List<AgentStreamEvent> events,
                                                     long measuredDurationMs) {
        List<String> toolSequence = events.stream()
                .filter(event -> "tool_start".equals(event.type()))
                .map(AgentStreamEvent::toolName)
                .toList();
        AgentStreamEvent terminal = events.stream()
                .filter(event -> "done".equals(event.type()) || "error".equals(event.type()))
                .reduce((first, second) -> second)
                .orElse(null);
        String sessionId = lastValue(events.stream().map(AgentStreamEvent::sessionId).toList());
        String runId = lastValue(events.stream().map(AgentStreamEvent::runId).toList());
        boolean completed = terminal != null && "done".equals(terminal.type());
        return new AgentBehaviorRunResult(
                sessionId,
                runId,
                completed,
                toolSequence,
                terminal != null && terminal.toolCallCount() != null
                        ? terminal.toolCallCount() : toolSequence.size(),
                terminal != null && terminal.modelTurnCount() != null
                        ? terminal.modelTurnCount() : 0,
                terminal != null && terminal.durationMs() != null
                        ? terminal.durationMs() : measuredDurationMs,
                terminal != null && "error".equals(terminal.type()) ? terminal.code() : null,
                terminal != null && "error".equals(terminal.type()) ? terminal.message() : null);
    }

    private static String lastValue(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank())
                .reduce((first, second) -> second).orElse(null);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
