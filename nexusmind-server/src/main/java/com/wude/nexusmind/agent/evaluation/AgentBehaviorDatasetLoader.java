package com.wude.nexusmind.agent.evaluation;

import com.wude.nexusmind.rag.evaluation.DatasetValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AgentBehaviorDatasetLoader {

    private static final Set<String> TOOL_NAMES = Set.of(
            "search_knowledge_base", "get_document_context");

    private final ObjectMapper objectMapper;

    public AgentBehaviorDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentBehaviorDataset load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new DatasetValidationException("Agent behavior dataset does not exist: " + path);
        }
        List<AgentBehaviorScenario> scenarios = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).isBlank()) {
                    continue;
                }
                int lineNumber = index + 1;
                AgentBehaviorScenario scenario;
                try {
                    scenario = objectMapper.readValue(lines.get(index), AgentBehaviorScenario.class);
                } catch (JacksonException exception) {
                    throw new DatasetValidationException(
                            "Malformed agent behavior JSONL at line " + lineNumber, exception);
                }
                validate(scenario, lineNumber, ids);
                scenarios.add(scenario);
            }
        } catch (IOException exception) {
            throw new DatasetValidationException("Failed to read agent behavior dataset: " + path, exception);
        }
        if (scenarios.isEmpty()) {
            throw new DatasetValidationException("Agent behavior dataset must not be empty");
        }
        return new AgentBehaviorDataset(datasetName(path), scenarios);
    }

    private static void validate(AgentBehaviorScenario scenario,
                                 int lineNumber,
                                 Set<String> ids) {
        if (scenario.id() == null || scenario.id().isBlank()) {
            throw invalid(lineNumber, "id must not be blank");
        }
        if (!ids.add(scenario.id())) {
            throw invalid(lineNumber, "duplicate id: " + scenario.id());
        }
        if (scenario.category() == null) {
            throw invalid(lineNumber, "category is required");
        }
        if (scenario.turns() == null || scenario.turns().isEmpty()) {
            throw invalid(lineNumber, "turns must not be empty");
        }
        if (scenario.expectSameSession() && scenario.turns().size() < 2) {
            throw invalid(lineNumber, "expectSameSession requires at least two turns");
        }
        for (int turnIndex = 0; turnIndex < scenario.turns().size(); turnIndex++) {
            AgentBehaviorTurn turn = scenario.turns().get(turnIndex);
            if (turn == null || turn.message() == null || turn.message().isBlank()) {
                throw invalid(lineNumber, "turn " + (turnIndex + 1) + " message must not be blank");
            }
            if (turn.message().length() > 4_000) {
                throw invalid(lineNumber, "turn " + (turnIndex + 1) + " message exceeds 4000 characters");
            }
            if (turn.expectedTools() == null) {
                throw invalid(lineNumber, "turn " + (turnIndex + 1) + " expectedTools is required");
            }
            for (String tool : turn.expectedTools()) {
                if (!TOOL_NAMES.contains(tool)) {
                    throw invalid(lineNumber, "unsupported expected tool: " + tool);
                }
            }
        }
    }

    private static DatasetValidationException invalid(int lineNumber, String message) {
        return new DatasetValidationException(
                "Invalid agent behavior scenario at line " + lineNumber + ": " + message);
    }

    private static String datasetName(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".jsonl")
                ? fileName.substring(0, fileName.length() - ".jsonl".length())
                : fileName;
    }
}
