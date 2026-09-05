package com.wude.nexusmind.knowledge.storage;

import com.wude.nexusmind.knowledge.config.StorageProperties;
import com.wude.nexusmind.knowledge.exception.DocumentStorageException;
import com.wude.nexusmind.knowledge.exception.DocumentTooLargeException;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

@Component
public class LocalDocumentStorage implements DocumentStorage {

    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_SAFE_FILE_NAME_LENGTH = 180;

    private final Path storageRoot;
    private final long maximumFileSize;

    public LocalDocumentStorage(StorageProperties properties) {
        this.storageRoot = properties.root().toAbsolutePath().normalize();
        this.maximumFileSize = properties.maxFileSize().toBytes();
    }

    @Override
    public StagedDocument stage(InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("Upload stream is required");
        }

        Path temporaryFile = null;
        try {
            Path temporaryDirectory = storageRoot.resolve(".tmp");
            Files.createDirectories(temporaryDirectory);
            temporaryFile = Files.createTempFile(temporaryDirectory, "upload-", ".tmp");

            MessageDigest digest = sha256Digest();
            long totalBytes = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = inputStream;
                 OutputStream output = Files.newOutputStream(temporaryFile)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > maximumFileSize) {
                        throw new DocumentTooLargeException(maximumFileSize);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            return new StagedDocument(temporaryFile, totalBytes, HexFormat.of().formatHex(digest.digest()));
        } catch (DocumentTooLargeException exception) {
            deleteTemporaryQuietly(temporaryFile);
            throw exception;
        } catch (IOException exception) {
            deleteTemporaryQuietly(temporaryFile);
            throw new DocumentStorageException("Failed to stage uploaded document", exception);
        }
    }

    @Override
    public StoredDocument commit(StagedDocument stagedDocument, long knowledgeBaseId, String originalFileName) {
        if (stagedDocument == null) {
            throw new IllegalArgumentException("Staged document is required");
        }
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("Knowledge base ID must be positive");
        }
        if (!stagedDocument.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 value");
        }

        String safeFileName = safeFileName(originalFileName);
        Path relativePath = Path.of("knowledge", Long.toString(knowledgeBaseId),
                stagedDocument.sha256(), safeFileName);
        Path target = resolveInsideRoot(relativePath);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                discard(stagedDocument);
                return new StoredDocument(toPortablePath(relativePath), false);
            }

            try {
                Files.move(stagedDocument.temporaryPath(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(stagedDocument.temporaryPath(), target);
            }
            return new StoredDocument(toPortablePath(relativePath), true);
        } catch (FileAlreadyExistsException exception) {
            discard(stagedDocument);
            return new StoredDocument(toPortablePath(relativePath), false);
        } catch (IOException exception) {
            throw new DocumentStorageException("Failed to store uploaded document", exception);
        }
    }

    @Override
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new DocumentStorageException("Document storage path is missing", null);
        }
        Path resolved = resolveInsideRoot(Path.of(relativePath));
        if (!Files.isRegularFile(resolved)) {
            throw new DocumentStorageException("Stored document does not exist", null);
        }
        return resolved;
    }

    @Override
    public void discard(StagedDocument stagedDocument) {
        if (stagedDocument != null) {
            deleteTemporaryQuietly(stagedDocument.temporaryPath());
        }
    }

    @Override
    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path target = resolveInsideRoot(Path.of(relativePath));
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new DocumentStorageException("Failed to delete stored document", exception);
        }
    }

    private Path resolveInsideRoot(Path relativePath) {
        if (relativePath.isAbsolute()) {
            throw new DocumentStorageException("Absolute storage paths are not allowed", null);
        }
        Path resolved = storageRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new DocumentStorageException("Storage path escapes the configured root", null);
        }
        return resolved;
    }

    private static String safeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank() || originalFileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Original file name is required");
        }
        String portableName = originalFileName.replace('\\', '/');
        String baseName = portableName.substring(portableName.lastIndexOf('/') + 1);
        String normalized = Normalizer.normalize(baseName.strip(), Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}._-]", "_")
                .replaceAll("_+", "_");
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) {
            throw new IllegalArgumentException("Original file name is invalid");
        }
        if (normalized.length() > MAX_SAFE_FILE_NAME_LENGTH) {
            int extensionStart = normalized.lastIndexOf('.');
            String extension = extensionStart > 0 ? normalized.substring(extensionStart) : "";
            int prefixLength = MAX_SAFE_FILE_NAME_LENGTH - extension.length();
            normalized = normalized.substring(0, Math.max(1, prefixLength)) + extension;
        }
        return normalized;
    }

    private static String toPortablePath(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void deleteTemporaryQuietly(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed upload.
        }
    }
}
