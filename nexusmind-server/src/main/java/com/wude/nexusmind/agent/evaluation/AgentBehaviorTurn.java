package com.wude.nexusmind.agent.evaluation;

import java.util.List;

public record AgentBehaviorTurn(
        String message,
        List<String> expectedTools) {

    public AgentBehaviorTurn {
        expectedTools = expectedTools == null ? null : List.copyOf(expectedTools);
    }
}
