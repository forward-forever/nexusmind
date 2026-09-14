package com.wude.nexusmind.rag.evaluation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BenchmarkSourceDatasetLoader {

    private final ObjectMapper objectMapper;

    public BenchmarkSourceDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<BenchmarkSourceCase> load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new DatasetValidationException("Benchmark source file does not exist: " + path);
        }

        List<BenchmarkSourceCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    continue;
                }
                int lineNumber = index + 1;
                BenchmarkSourceCase sourceCase;
                try {
                    sourceCase = objectMapper.readValue(line, BenchmarkSourceCase.class);
                } catch (JacksonException exception) {
                    throw invalid(lineNumber, "malformed JSON or unsupported category", exception);
                }
                cases.add(validateAndNormalize(sourceCase, lineNumber, ids));
            }
        } catch (IOException exception) {
            throw new DatasetValidationException("Failed to read benchmark source: " + path, exception);
        }
        if (cases.isEmpty()) {
            throw new DatasetValidationException("Benchmark source must contain at least one question");
        }
        return List.copyOf(cases);
    }

    private static BenchmarkSourceCase validateAndNormalize(BenchmarkSourceCase sourceCase,
                                                            int lineNumber,
                                                            Set<String> ids) {
        if (sourceCase.id() == null || sourceCase.id().isBlank()) {
            throw invalid(lineNumber, "id must not be blank");
        }
        String normalizedId = sourceCase.id().trim();
        if (!ids.add(normalizedId)) {
            throw invalid(lineNumber, "duplicate id: " + normalizedId);
        }
        if (sourceCase.question() == null || sourceCase.question().isBlank()) {
            throw invalid(lineNumber, "question must not be blank");
        }
        if (sourceCase.category() == null) {
            throw invalid(lineNumber, "category is required");
        }
        if (sourceCase.expectedPages() == null || sourceCase.expectedPages().isEmpty()) {
            throw invalid(lineNumber, "expectedPages must not be empty");
        }

        LinkedHashSet<Integer> uniquePages = new LinkedHashSet<>();
        for (Integer page : sourceCase.expectedPages()) {
            if (page == null || page <= 0) {
                throw invalid(lineNumber, "expectedPages must contain positive page numbers");
            }
            uniquePages.add(page);
        }
        return new BenchmarkSourceCase(
                normalizedId,
                sourceCase.question().trim(),
                sourceCase.category(),
                List.copyOf(uniquePages),
                blankToNull(sourceCase.expectedConcept()),
                blankToNull(sourceCase.note()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DatasetValidationException invalid(int lineNumber, String message) {
        return new DatasetValidationException(
                "Invalid benchmark source case at line " + lineNumber + ": " + message);
    }

    private static DatasetValidationException invalid(int lineNumber,
                                                      String message,
                                                      Throwable cause) {
        return new DatasetValidationException(
                "Invalid benchmark source case at line " + lineNumber + ": " + message, cause);
    }
}
