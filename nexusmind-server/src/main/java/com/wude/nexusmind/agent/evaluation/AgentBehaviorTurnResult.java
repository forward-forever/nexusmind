package com.wude.nexusmind.agent.evaluation;

import java.util.List;

public record AgentBehaviorTurnResult(
        int turn,
        String message,
        List<String> expectedTools,
        List<String> actualTools,
        boolean toolSequenceMatched,
        boolean completed,
        String sessionId,
        String runId,
        int toolCallCount,
        int modelTurnCount,
        long durationMs,
        String errorCode,
        String errorMessage) {

    public AgentBehaviorTurnResult {
        expectedTools = List.copyOf(expectedTools);
        actualTools = List.copyOf(actualTools);
    }
}
