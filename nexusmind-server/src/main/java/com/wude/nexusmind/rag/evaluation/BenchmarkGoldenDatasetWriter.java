package com.wude.nexusmind.rag.evaluation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class BenchmarkGoldenDatasetWriter {

    private final ObjectMapper objectMapper;

    public BenchmarkGoldenDatasetWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path write(List<RetrievalEvaluationCase> cases, Path output) {
        if (cases == null || cases.isEmpty()) {
            throw new DatasetValidationException("Cannot write an empty Golden Dataset");
        }
        try {
            Path absoluteOutput = output.toAbsolutePath().normalize();
            Path parent = absoluteOutput.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder jsonl = new StringBuilder();
            for (RetrievalEvaluationCase evaluationCase : cases) {
                jsonl.append(objectMapper.writeValueAsString(evaluationCase)).append('\n');
            }
            Files.writeString(absoluteOutput, jsonl, StandardCharsets.UTF_8);
            return absoluteOutput;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialize resolved Golden Dataset", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write resolved Golden Dataset: " + output, exception);
        }
    }
}
