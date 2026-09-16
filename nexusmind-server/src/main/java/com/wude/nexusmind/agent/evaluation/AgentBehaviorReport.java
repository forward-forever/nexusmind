package com.wude.nexusmind.agent.evaluation;

import java.util.List;

public record AgentBehaviorReport(
        String timestamp,
        String datasetName,
        long knowledgeBaseId,
        AgentBehaviorMetrics metrics,
        List<AgentBehaviorScenarioResult> scenarios,
        List<AgentBehaviorScenarioResult> failures) {

    public AgentBehaviorReport {
        scenarios = List.copyOf(scenarios);
        failures = List.copyOf(failures);
    }
}
