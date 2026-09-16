package com.wude.nexusmind.agent.evaluation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentBehaviorEvaluationService {

    private final AgentBehaviorExecutor executor;
    private final Clock clock;

    public AgentBehaviorEvaluationService(AgentBehaviorExecutor executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    public AgentBehaviorReport evaluate(AgentBehaviorDataset dataset, long knowledgeBaseId) {
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId must be positive");
        }
        List<AgentBehaviorScenarioResult> results = dataset.scenarios().stream()
                .map(scenario -> evaluateScenario(scenario, knowledgeBaseId))
                .toList();
        List<AgentBehaviorScenarioResult> failures = results.stream()
                .filter(result -> !result.completionMatched()
                        || !result.toolSequenceMatched()
                        || !result.sessionContinuityPassed())
                .toList();
        return new AgentBehaviorReport(
                Instant.now(clock).toString(), dataset.name(), knowledgeBaseId,
                metrics(results), results, failures);
    }

    private AgentBehaviorScenarioResult evaluateScenario(AgentBehaviorScenario scenario,
                                                          long knowledgeBaseId) {
        List<AgentBehaviorTurnResult> turns = new ArrayList<>();
        String sessionId = null;
        for (int index = 0; index < scenario.turns().size(); index++) {
            AgentBehaviorTurn turn = scenario.turns().get(index);
            AgentBehaviorRunResult run = executor.execute(
                    knowledgeBaseId, sessionId, turn.message());
            if (sessionId == null && run.sessionId() != null) {
                sessionId = run.sessionId();
            }
            turns.add(new AgentBehaviorTurnResult(
                    index + 1,
                    turn.message(),
                    turn.expectedTools(),
                    run.toolSequence(),
                    turn.expectedTools().equals(run.toolSequence()),
                    run.completed(),
                    run.sessionId(),
                    run.runId(),
                    run.toolCallCount(),
                    run.modelTurnCount(),
                    run.durationMs(),
                    run.errorCode(),
                    run.errorMessage()));
            if (!run.completed()) {
                break;
            }
        }

        boolean actualCompletion = turns.size() == scenario.turns().size()
                && turns.stream().allMatch(AgentBehaviorTurnResult::completed);
        boolean completionMatched = actualCompletion == scenario.expectCompletion();
        boolean toolSequenceMatched = turns.size() == scenario.turns().size()
                && turns.stream().allMatch(AgentBehaviorTurnResult::toolSequenceMatched);
        boolean sessionContinuityPassed = !scenario.expectSameSession()
                || hasOneSession(turns);
        int unexpectedTools = turns.stream()
                .mapToInt(AgentBehaviorEvaluationService::unexpectedTools)
                .sum();
        return new AgentBehaviorScenarioResult(
                scenario.id(), scenario.category(), actualCompletion, completionMatched,
                toolSequenceMatched, sessionContinuityPassed,
                unexpectedTools, turns, scenario.note());
    }

    private static AgentBehaviorMetrics metrics(List<AgentBehaviorScenarioResult> results) {
        int scenarioCount = results.size();
        List<AgentBehaviorTurnResult> turns = results.stream()
                .flatMap(result -> result.turns().stream()).toList();
        int continuityTotal = (int) results.stream()
                .filter(result -> result.turns().size() > 1).count();
        int continuityPassed = (int) results.stream()
                .filter(result -> result.turns().size() > 1)
                .filter(AgentBehaviorScenarioResult::sessionContinuityPassed).count();
        return new AgentBehaviorMetrics(
                scenarioCount,
                average(results.stream().filter(AgentBehaviorScenarioResult::completed).count(), scenarioCount),
                average(results.stream().filter(AgentBehaviorScenarioResult::toolSequenceMatched).count(), scenarioCount),
                results.stream().mapToInt(AgentBehaviorScenarioResult::unexpectedToolCallCount).sum(),
                turns.stream().mapToInt(AgentBehaviorTurnResult::toolCallCount).average().orElse(0),
                turns.stream().mapToInt(AgentBehaviorTurnResult::modelTurnCount).average().orElse(0),
                turns.stream().mapToLong(AgentBehaviorTurnResult::durationMs).average().orElse(0),
                continuityPassed,
                continuityTotal);
    }

    private static boolean hasOneSession(List<AgentBehaviorTurnResult> turns) {
        if (turns.size() < 2 || turns.stream().anyMatch(turn -> turn.sessionId() == null)) {
            return false;
        }
        return turns.stream().map(AgentBehaviorTurnResult::sessionId).distinct().count() == 1;
    }

    private static int unexpectedTools(AgentBehaviorTurnResult turn) {
        Map<String, Integer> expectedCounts = new HashMap<>();
        turn.expectedTools().forEach(tool -> expectedCounts.merge(tool, 1, Integer::sum));
        int unexpected = 0;
        for (String tool : turn.actualTools()) {
            int remaining = expectedCounts.getOrDefault(tool, 0);
            if (remaining == 0) {
                unexpected++;
            } else {
                expectedCounts.put(tool, remaining - 1);
            }
        }
        return unexpected;
    }

    private static double average(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
