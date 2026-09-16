package com.wude.nexusmind.agent.evaluation;

public record AgentBehaviorMetrics(
        int scenarioCount,
        double completionRate,
        double toolSequenceMatchRate,
        int unexpectedToolCallCount,
        double averageToolCalls,
        double averageModelTurns,
        double averageDurationMs,
        int sessionContinuityPassed,
        int sessionContinuityTotal) {
}
