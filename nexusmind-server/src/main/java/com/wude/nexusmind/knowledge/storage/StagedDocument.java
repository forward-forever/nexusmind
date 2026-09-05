package com.wude.nexusmind.knowledge.storage;

import java.nio.file.Path;

public record StagedDocument(Path temporaryPath, long fileSize, String sha256) {
}
