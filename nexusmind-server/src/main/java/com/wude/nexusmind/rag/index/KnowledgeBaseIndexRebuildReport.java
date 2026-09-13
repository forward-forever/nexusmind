package com.wude.nexusmind.rag.index;

import java.util.List;

public record KnowledgeBaseIndexRebuildReport(
        long knowledgeBaseId,
        int total,
        int indexed,
        List<Failure> failures
) {
    public KnowledgeBaseIndexRebuildReport {
        failures = List.copyOf(failures);
    }

    public int failed() {
        return failures.size();
    }

    public record Failure(long documentId, String message) {
    }
}
