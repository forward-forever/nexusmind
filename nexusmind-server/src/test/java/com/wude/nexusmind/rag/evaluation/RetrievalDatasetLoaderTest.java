package com.wude.nexusmind.rag.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalDatasetLoaderTest {

    @TempDir
    Path temporaryDirectory;

    private final RetrievalDatasetLoader loader = new RetrievalDatasetLoader(new ObjectMapper());

    @Test
    void loadsValidJsonlAndDerivesDatasetName() throws IOException {
        Path dataset = write("java-backend.jsonl", """
                {"id":"q001","knowledgeBaseId":12,"question":"What is MVCC?","relevantChunkIds":[101,102],"category":"ABBREVIATION","note":"manual"}
                {"id":"q002","knowledgeBaseId":12,"question":"Explain transaction visibility","relevantChunkIds":[103],"category":"SEMANTIC"}
                """);

        RetrievalEvaluationDataset loaded = loader.load(dataset);

        assertThat(loaded.name()).isEqualTo("java-backend");
        assertThat(loaded.cases()).hasSize(2);
        assertThat(loaded.cases().get(0).relevantChunkIds()).containsExactly(101L, 102L);
        assertThat(loaded.cases().get(1).note()).isNull();
    }

    @Test
    void rejectsDuplicateIds() throws IOException {
        Path dataset = write("duplicate.jsonl", validLine("q001") + validLine("q001"));

        assertThatThrownBy(() -> loader.load(dataset))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("duplicate id: q001");
    }

    @Test
    void rejectsBlankQuestion() throws IOException {
        Path dataset = write("blank.jsonl", """
                {"id":"q001","knowledgeBaseId":12,"question":"  ","relevantChunkIds":[101],"category":"EXACT"}
                """);

        assertThatThrownBy(() -> loader.load(dataset))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("question must not be blank");
    }

    @Test
    void rejectsEmptyOrDuplicateRelevantChunks() throws IOException {
        Path empty = write("empty-labels.jsonl", """
                {"id":"q001","knowledgeBaseId":12,"question":"question","relevantChunkIds":[],"category":"EXACT"}
                """);
        Path duplicate = write("duplicate-labels.jsonl", """
                {"id":"q002","knowledgeBaseId":12,"question":"question","relevantChunkIds":[101,101],"category":"EXACT"}
                """);

        assertThatThrownBy(() -> loader.load(empty))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("relevantChunkIds must not be empty");
        assertThatThrownBy(() -> loader.load(duplicate))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("must not contain duplicates");
    }

    @Test
    void reportsMalformedJsonWithItsLineNumber() throws IOException {
        Path dataset = write("malformed.jsonl", validLine("q001") + "{not-json}\n");

        assertThatThrownBy(() -> loader.load(dataset))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("Malformed JSONL at line 2");
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(temporaryDirectory.resolve(name), content);
    }

    private static String validLine(String id) {
        return "{\"id\":\"%s\",\"knowledgeBaseId\":12,\"question\":\"question\","
                .formatted(id)
                + "\"relevantChunkIds\":[101],\"category\":\"EXACT\"}\n";
    }
}
