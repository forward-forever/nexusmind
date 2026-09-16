package com.wude.nexusmind.agent.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class AgentBehaviorReportWriter {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;

    public AgentBehaviorReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentBehaviorReportFiles write(AgentBehaviorReport report, Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            String baseName = "agent-behavior-"
                    + FILE_TIMESTAMP.format(Instant.parse(report.timestamp()));
            Path json = outputDirectory.resolve(baseName + ".json");
            Path markdown = outputDirectory.resolve(baseName + ".md");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
            Files.writeString(markdown, toMarkdown(report), StandardCharsets.UTF_8);
            return new AgentBehaviorReportFiles(json.toAbsolutePath(), markdown.toAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write Agent behavior report", exception);
        }
    }

    String toMarkdown(AgentBehaviorReport report) {
        AgentBehaviorMetrics metrics = report.metrics();
        StringBuilder markdown = new StringBuilder()
                .append("# Agent Behavior Evaluation\n\n")
                .append("- Timestamp: ").append(report.timestamp()).append('\n')
                .append("- Dataset: ").append(report.datasetName()).append('\n')
                .append("- Knowledge Base: ").append(report.knowledgeBaseId()).append('\n')
                .append("- Scenarios: ").append(metrics.scenarioCount()).append("\n\n")
                .append("## Behavior Metrics\n\n")
                .append(String.format(Locale.ROOT, "- Completion Rate: %.4f%n", metrics.completionRate()))
                .append(String.format(Locale.ROOT, "- Tool Sequence Match Rate: %.4f%n", metrics.toolSequenceMatchRate()))
                .append("- Unexpected Tool Calls: ").append(metrics.unexpectedToolCallCount()).append('\n')
                .append(String.format(Locale.ROOT, "- Average Tool Calls: %.2f%n", metrics.averageToolCalls()))
                .append(String.format(Locale.ROOT, "- Average Model Turns: %.2f%n", metrics.averageModelTurns()))
                .append(String.format(Locale.ROOT, "- Average Duration: %.2f ms%n", metrics.averageDurationMs()))
                .append("- Session Continuity: ").append(metrics.sessionContinuityPassed())
                .append('/').append(metrics.sessionContinuityTotal()).append("\n\n")
                .append("## Scenario Results\n\n")
                .append("| Scenario | Category | Completed | Completion Expected | Tool Sequence | Session |\n")
                .append("|---|---|---:|---:|---:|---:|\n");
        for (AgentBehaviorScenarioResult result : report.scenarios()) {
            markdown.append("| ").append(result.id()).append(" | ")
                    .append(result.category()).append(" | ")
                    .append(result.completed() ? "YES" : "NO").append(" | ")
                    .append(pass(result.completionMatched())).append(" | ")
                    .append(pass(result.toolSequenceMatched())).append(" | ")
                    .append(pass(result.sessionContinuityPassed())).append(" |\n");
        }
        markdown.append("\n## Failures\n\n");
        if (report.failures().isEmpty()) {
            markdown.append("No objective behavior failures.\n");
        } else {
            for (AgentBehaviorScenarioResult failure : report.failures()) {
                markdown.append("### ").append(failure.id()).append("\n\n")
                        .append("- Category: ").append(failure.category()).append('\n');
                for (AgentBehaviorTurnResult turn : failure.turns()) {
                    markdown.append("- Turn ").append(turn.turn())
                            .append(" expected: ").append(turn.expectedTools())
                            .append("; actual: ").append(turn.actualTools())
                            .append("; completed: ").append(turn.completed()).append('\n');
                }
                markdown.append('\n');
            }
        }
        markdown.append("> This report evaluates observable Agent behavior only; it does not judge answer quality.\n");
        return markdown.toString();
    }

    private static String pass(boolean value) {
        return value ? "PASS" : "FAIL";
    }
}
