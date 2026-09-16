package com.wude.nexusmind.agent.evaluation;

import java.util.List;

public record AgentBehaviorRunResult(
        String sessionId,
        String runId,
        boolean completed,
        List<String> toolSequence,
        int toolCallCount,
        int modelTurnCount,
        long durationMs,
        String errorCode,
        String errorMessage) {

    public AgentBehaviorRunResult {
        toolSequence = List.copyOf(toolSequence);
    }
}
