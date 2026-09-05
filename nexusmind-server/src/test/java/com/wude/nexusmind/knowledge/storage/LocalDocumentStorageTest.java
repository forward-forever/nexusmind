package com.wude.nexusmind.knowledge.storage;

import com.wude.nexusmind.knowledge.config.StorageProperties;
import com.wude.nexusmind.knowledge.exception.DocumentStorageException;
import com.wude.nexusmind.knowledge.exception.DocumentTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentStorageTest {

    @TempDir
    Path storageRoot;

    @Test
    void stagesHashesAndStoresUsingRelativeSafePath() throws Exception {
        LocalDocumentStorage storage = storage(1024);
        byte[] content = "NexusMind storage".getBytes(StandardCharsets.UTF_8);

        StagedDocument staged = storage.stage(new ByteArrayInputStream(content));
        StoredDocument stored = storage.commit(staged, 42, "../../unsafe notes.txt");

        assertThat(staged.sha256()).hasSize(64);
        assertThat(stored.relativePath()).startsWith("knowledge/42/").endsWith("unsafe_notes.txt");
        assertThat(stored.relativePath()).doesNotContain("..");
        assertThat(storage.resolve(stored.relativePath())).startsWith(storageRoot.toAbsolutePath());
        assertThat(Files.readString(storage.resolve(stored.relativePath()))).isEqualTo("NexusMind storage");
    }

    @Test
    void absoluteAndTraversalDatabasePathsAreRejected() {
        LocalDocumentStorage storage = storage(1024);

        assertThatThrownBy(() -> storage.resolve("/tmp/file.txt"))
                .isInstanceOf(DocumentStorageException.class);
        assertThatThrownBy(() -> storage.resolve("../../file.txt"))
                .isInstanceOf(DocumentStorageException.class);
    }

    @Test
    void streamingLimitDeletesOversizedTemporaryFile() throws Exception {
        LocalDocumentStorage storage = storage(5);

        assertThatThrownBy(() -> storage.stage(new ByteArrayInputStream(new byte[6])))
                .isInstanceOf(DocumentTooLargeException.class);
        Path temporaryDirectory = storageRoot.resolve(".tmp");
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    private LocalDocumentStorage storage(long maximumBytes) {
        return new LocalDocumentStorage(
                new StorageProperties(storageRoot, DataSize.ofBytes(maximumBytes))
        );
    }
}
