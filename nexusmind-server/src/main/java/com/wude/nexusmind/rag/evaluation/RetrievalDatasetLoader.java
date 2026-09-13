package com.wude.nexusmind.rag.evaluation;

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

public class RetrievalDatasetLoader {

    private final ObjectMapper objectMapper;

    public RetrievalDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RetrievalEvaluationDataset load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new DatasetValidationException("Dataset file does not exist: " + path);
        }

        List<RetrievalEvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    continue;
                }
                int lineNumber = index + 1;
                RetrievalEvaluationCase evaluationCase;
                try {
                    evaluationCase = objectMapper.readValue(line, RetrievalEvaluationCase.class);
                } catch (JacksonException exception) {
                    throw new DatasetValidationException(
                            "Malformed JSONL at line " + lineNumber, exception);
                }
                validateCase(evaluationCase, lineNumber, ids);
                cases.add(evaluationCase);
            }
        } catch (IOException exception) {
            throw new DatasetValidationException("Failed to read dataset: " + path, exception);
        }
        if (cases.isEmpty()) {
            throw new DatasetValidationException("Dataset must contain at least one evaluation case");
        }
        return new RetrievalEvaluationDataset(datasetName(path), cases);
    }

    private static void validateCase(RetrievalEvaluationCase evaluationCase,
                                     int lineNumber,
                                     Set<String> ids) {
        if (evaluationCase.id() == null || evaluationCase.id().isBlank()) {
            throw invalid(lineNumber, "id must not be blank");
        }
        if (!ids.add(evaluationCase.id())) {
            throw invalid(lineNumber, "duplicate id: " + evaluationCase.id());
        }
        if (evaluationCase.knowledgeBaseId() <= 0) {
            throw invalid(lineNumber, "knowledgeBaseId must be positive");
        }
        if (evaluationCase.question() == null || evaluationCase.question().isBlank()) {
            throw invalid(lineNumber, "question must not be blank");
        }
        if (evaluationCase.relevantChunkIds() == null || evaluationCase.relevantChunkIds().isEmpty()) {
            throw invalid(lineNumber, "relevantChunkIds must not be empty");
        }
        Set<Long> relevantIds = new HashSet<>();
        for (Long chunkId : evaluationCase.relevantChunkIds()) {
            if (chunkId == null || chunkId <= 0) {
                throw invalid(lineNumber, "relevantChunkIds must contain positive IDs");
            }
            if (!relevantIds.add(chunkId)) {
                throw invalid(lineNumber, "relevantChunkIds must not contain duplicates");
            }
        }
        if (evaluationCase.category() == null) {
            throw invalid(lineNumber, "category is required");
        }
    }

    private static DatasetValidationException invalid(int lineNumber, String message) {
        return new DatasetValidationException("Invalid dataset case at line " + lineNumber + ": " + message);
    }

    private static String datasetName(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".jsonl")
                ? fileName.substring(0, fileName.length() - ".jsonl".length())
                : fileName;
    }
}
