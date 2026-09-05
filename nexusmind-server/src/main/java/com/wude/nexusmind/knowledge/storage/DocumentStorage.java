package com.wude.nexusmind.knowledge.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface DocumentStorage {

    StagedDocument stage(InputStream inputStream);

    StoredDocument commit(StagedDocument stagedDocument, long knowledgeBaseId, String originalFileName);

    Path resolve(String relativePath);

    void discard(StagedDocument stagedDocument);

    void delete(String relativePath);
}
