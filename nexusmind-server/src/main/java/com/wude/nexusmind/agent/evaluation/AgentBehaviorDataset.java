package com.wude.nexusmind.agent.evaluation;

import java.util.List;

public record AgentBehaviorDataset(String name, List<AgentBehaviorScenario> scenarios) {

    public AgentBehaviorDataset {
        scenarios = List.copyOf(scenarios);
    }
}
