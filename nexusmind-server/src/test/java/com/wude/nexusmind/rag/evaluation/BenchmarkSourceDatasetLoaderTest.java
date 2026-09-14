package com.wude.nexusmind.rag.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkSourceDatasetLoaderTest {

    @TempDir
    Path temporaryDirectory;

    private final BenchmarkSourceDatasetLoader loader =
            new BenchmarkSourceDatasetLoader(new ObjectMapper());

    @Test
    void loadsValidSourceAndDeduplicatesPagesInStableOrder() throws IOException {
        Path source = write("""
                {"id":"q001","question":"What is MVCC?","category":"ABBREVIATION","expectedPages":[4,5,4],"expectedConcept":"MVCC","note":"manual"}
                """);

        BenchmarkSourceCase sourceCase = loader.load(source).get(0);

        assertThat(sourceCase.expectedPages()).containsExactly(4, 5);
        assertThat(sourceCase.expectedConcept()).isEqualTo("MVCC");
        assertThat(sourceCase.note()).isEqualTo("manual");
    }

    @Test
    void rejectsDuplicateQueryId() throws IOException {
        Path source = write(validLine("q001") + validLine(" q001 "));

        assertThatThrownBy(() -> loader.load(source))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("duplicate id: q001");
    }

    @Test
    void rejectsEmptyExpectedPages() throws IOException {
        Path source = write("""
                {"id":"q001","question":"question","category":"EXACT","expectedPages":[]}
                """);

        assertThatThrownBy(() -> loader.load(source))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("expectedPages must not be empty");
    }

    @Test
    void rejectsNonPositivePage() throws IOException {
        Path source = write("""
                {"id":"q001","question":"question","category":"EXACT","expectedPages":[0]}
                """);

        assertThatThrownBy(() -> loader.load(source))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("positive page numbers");
    }

    @Test
    void rejectsBlankQuestion() throws IOException {
        Path source = write("""
                {"id":"q001","question":"  ","category":"EXACT","expectedPages":[1]}
                """);

        assertThatThrownBy(() -> loader.load(source))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("question must not be blank");
    }

    @Test
    void rejectsMalformedJsonOrUnsupportedCategoryWithLineNumber() throws IOException {
        Path source = write("""
                {"id":"q001","question":"question","category":"NOT_REAL","expectedPages":[1]}
                """);

        assertThatThrownBy(() -> loader.load(source))
                .isInstanceOf(DatasetValidationException.class)
                .hasMessageContaining("line 1")
                .hasMessageContaining("unsupported category");
    }

    private Path write(String content) throws IOException {
        return Files.writeString(temporaryDirectory.resolve("source.jsonl"), content);
    }

    private static String validLine(String id) {
        return "{\"id\":\"%s\",\"question\":\"question\",\"category\":\"EXACT\",\"expectedPages\":[1]}%n"
                .formatted(id);
    }
}
