package com.wude.nexusmind.rag.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkGoldenDatasetWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void outputIsReadableByExistingEvaluationDatasetLoader() {
        ObjectMapper objectMapper = new ObjectMapper();
        BenchmarkGoldenDatasetWriter writer = new BenchmarkGoldenDatasetWriter(objectMapper);
        RetrievalDatasetLoader existingLoader = new RetrievalDatasetLoader(objectMapper);
        RetrievalEvaluationCase goldenCase = new RetrievalEvaluationCase(
                "q001", 12, "What is MVCC?", List.of(101L, 102L),
                QueryCategory.ABBREVIATION, "manual");
        Path output = temporaryDirectory.resolve("local-golden.jsonl");

        writer.write(List.of(goldenCase), output);
        RetrievalEvaluationDataset loaded = existingLoader.load(output);

        assertThat(loaded.name()).isEqualTo("local-golden");
        assertThat(loaded.cases()).containsExactly(goldenCase);
    }
}
