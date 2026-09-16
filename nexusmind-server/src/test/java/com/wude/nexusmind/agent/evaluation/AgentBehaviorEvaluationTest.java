package com.wude.nexusmind.agent.evaluation;

import com.wude.nexusmind.rag.evaluation.DatasetValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBehaviorEvaluationTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsValidatedJsonlAndRejectsDuplicateIds() throws IOException {
        Path valid = write("agent.jsonl", direct("a001") + search("a002"));
        AgentBehaviorDataset loaded = new AgentBehaviorDatasetLoader(objectMapper).load(valid);
        assertThat(loaded.name()).isEqualTo("agent");
        assertThat(loaded.scenarios()).hasSize(2);
        assertThat(loaded.scenarios().get(1).turns().get(0).expectedTools())
                .containsExactly("search_knowledge_base");

        Path duplicate = write("duplicate.jsonl", direct("a001") + direct("a001"));
        assertThatThrownBy(() -> new AgentBehaviorDatasetLoader(objectMapper).load(duplicate))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("duplicate id");
    }

    @Test
    void rejectsMalformedTurnsAndUnsupportedToolLabels() throws IOException {
        Path emptyTurns = write("empty.jsonl", """
                {"id":"a001","category":"DIRECT","turns":[],"expectSameSession":false,"expectCompletion":true}
                """);
        Path unknownTool = write("unknown.jsonl", """
                {"id":"a002","category":"SEARCH","turns":[{"message":"q","expectedTools":["web_search"]}],"expectSameSession":false,"expectCompletion":true}
                """);
        assertThatThrownBy(() -> new AgentBehaviorDatasetLoader(objectMapper).load(emptyTurns))
                .hasMessageContaining("turns must not be empty");
        assertThatThrownBy(() -> new AgentBehaviorDatasetLoader(objectMapper).load(unknownTool))
                .hasMessageContaining("unsupported expected tool");
    }

    @Test
    void calculatesObjectiveBehaviorMetricsAndSessionContinuity() {
        QueueExecutor executor = new QueueExecutor(List.of(
                run("s1", "r1", List.of(), 0, 1, 100),
                run("s2", "r2", List.of(), 0, 1, 80),
                run("s2", "r3", List.of(), 0, 1, 120),
                run("s3", "r4", List.of("get_document_context"), 1, 2, 200)));
        AgentBehaviorDataset dataset = new AgentBehaviorDataset("fixture", List.of(
                scenario("direct", AgentBehaviorCategory.DIRECT, false,
                        new AgentBehaviorTurn("hello", List.of())),
                scenario("memory", AgentBehaviorCategory.MEMORY, true,
                        new AgentBehaviorTurn("remember", List.of()),
                        new AgentBehaviorTurn("recall", List.of())),
                scenario("mismatch", AgentBehaviorCategory.SEARCH, false,
                        new AgentBehaviorTurn("search", List.of("search_knowledge_base")))));
        AgentBehaviorEvaluationService service = new AgentBehaviorEvaluationService(
                executor, Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC));

        AgentBehaviorReport report = service.evaluate(dataset, 33L);

        assertThat(report.metrics().scenarioCount()).isEqualTo(3);
        assertThat(report.metrics().completionRate()).isEqualTo(1.0);
        assertThat(report.metrics().toolSequenceMatchRate()).isEqualTo(2.0 / 3.0);
        assertThat(report.metrics().unexpectedToolCallCount()).isEqualTo(1);
        assertThat(report.metrics().averageToolCalls()).isEqualTo(0.25);
        assertThat(report.metrics().averageModelTurns()).isEqualTo(1.25);
        assertThat(report.metrics().averageDurationMs()).isEqualTo(125.0);
        assertThat(report.metrics().sessionContinuityPassed()).isEqualTo(1);
        assertThat(report.failures()).extracting(AgentBehaviorScenarioResult::id)
                .containsExactly("mismatch");
    }

    @Test
    void writesJsonAndMarkdownFromDeterministicFakeEvaluation() throws IOException {
        AgentBehaviorEvaluationService service = new AgentBehaviorEvaluationService(
                new QueueExecutor(List.of(run(
                        "session", "run", List.of("search_knowledge_base"), 1, 2, 42))),
                Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC));
        AgentBehaviorDataset dataset = new AgentBehaviorDataset("offline", List.of(
                scenario("search", AgentBehaviorCategory.SEARCH, false,
                        new AgentBehaviorTurn("find", List.of("search_knowledge_base")))));

        AgentBehaviorReport report = service.evaluate(dataset, 33L);
        AgentBehaviorReportFiles files = new AgentBehaviorReportWriter(objectMapper)
                .write(report, temporaryDirectory.resolve("reports"));

        assertThat(files.json()).isRegularFile();
        assertThat(files.markdown()).isRegularFile();
        assertThat(Files.readString(files.markdown()))
                .contains("# Agent Behavior Evaluation")
                .contains("Tool Sequence Match Rate: 1.0000")
                .contains("Average Duration: 42.00 ms")
                .contains("No objective behavior failures");
        assertThat(objectMapper.readTree(files.json().toFile()).get("datasetName").asText())
                .isEqualTo("offline");
    }

    @Test
    void completionRateMeasuresActualCompletionWhileExpectationControlsFailure() {
        AgentBehaviorRunResult expectedFailure = new AgentBehaviorRunResult(
                "session", "run", false, List.of(), 0, 1,
                25, "EXPECTED_FAILURE", "fixture");
        AgentBehaviorScenario scenario = new AgentBehaviorScenario(
                "expected-failure", AgentBehaviorCategory.GUARDRAIL,
                List.of(new AgentBehaviorTurn("guardrail", List.of())),
                false, false, null);

        AgentBehaviorReport report = new AgentBehaviorEvaluationService(
                new QueueExecutor(List.of(expectedFailure)),
                Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC))
                .evaluate(new AgentBehaviorDataset("fixture", List.of(scenario)), 33L);

        assertThat(report.metrics().completionRate()).isZero();
        assertThat(report.scenarios().get(0).completionMatched()).isTrue();
        assertThat(report.failures()).isEmpty();
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(temporaryDirectory.resolve(name), content);
    }

    private static AgentBehaviorScenario scenario(String id,
                                                  AgentBehaviorCategory category,
                                                  boolean sameSession,
                                                  AgentBehaviorTurn... turns) {
        return new AgentBehaviorScenario(
                id, category, List.of(turns), sameSession, true, null);
    }

    private static AgentBehaviorRunResult run(String sessionId,
                                              String runId,
                                              List<String> tools,
                                              int toolCalls,
                                              int modelTurns,
                                              long durationMs) {
        return new AgentBehaviorRunResult(
                sessionId, runId, true, tools, toolCalls, modelTurns,
                durationMs, null, null);
    }

    private static String direct(String id) {
        return "{\"id\":\"%s\",\"category\":\"DIRECT\",\"turns\":[{\"message\":\"hello\",\"expectedTools\":[]}],\"expectSameSession\":false,\"expectCompletion\":true}%n"
                .formatted(id);
    }

    private static String search(String id) {
        return "{\"id\":\"%s\",\"category\":\"SEARCH\",\"turns\":[{\"message\":\"find\",\"expectedTools\":[\"search_knowledge_base\"]}],\"expectSameSession\":false,\"expectCompletion\":true}%n"
                .formatted(id);
    }

    private static final class QueueExecutor implements AgentBehaviorExecutor {

        private final Deque<AgentBehaviorRunResult> results;

        private QueueExecutor(List<AgentBehaviorRunResult> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public AgentBehaviorRunResult execute(long knowledgeBaseId,
                                              String sessionId,
                                              String message) {
            return results.removeFirst();
        }
    }
}
