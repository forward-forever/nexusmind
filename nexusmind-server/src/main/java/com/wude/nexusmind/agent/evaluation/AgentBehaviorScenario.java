package com.wude.nexusmind.agent.evaluation;

import java.util.List;

public record AgentBehaviorScenario(
        String id,
        AgentBehaviorCategory category,
        List<AgentBehaviorTurn> turns,
        boolean expectSameSession,
        boolean expectCompletion,
        String note) {

    public AgentBehaviorScenario {
        turns = turns == null ? null : List.copyOf(turns);
    }
}
