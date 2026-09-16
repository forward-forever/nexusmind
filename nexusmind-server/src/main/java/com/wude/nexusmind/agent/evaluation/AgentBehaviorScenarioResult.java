package com.wude.nexusmind.agent.evaluation;

import java.util.List;

public record AgentBehaviorScenarioResult(
        String id,
        AgentBehaviorCategory category,
        boolean completed,
        boolean completionMatched,
        boolean toolSequenceMatched,
        boolean sessionContinuityPassed,
        int unexpectedToolCallCount,
        List<AgentBehaviorTurnResult> turns,
        String note) {

    public AgentBehaviorScenarioResult {
        turns = List.copyOf(turns);
    }
}
