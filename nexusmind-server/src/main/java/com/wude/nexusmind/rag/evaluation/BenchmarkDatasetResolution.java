package com.wude.nexusmind.rag.evaluation;

import java.util.List;

public record BenchmarkDatasetResolution(
        long documentId,
        long knowledgeBaseId,
        List<RetrievalEvaluationCase> cases,
        List<QueryMapping> mappings,
        int referencedPageCount,
        int resolvedChunkCount) {

    public BenchmarkDatasetResolution {
        cases = List.copyOf(cases);
        mappings = List.copyOf(mappings);
    }

    public record QueryMapping(
            String queryId,
            List<Integer> expectedPages,
            List<Long> relevantChunkIds) {

        public QueryMapping {
            expectedPages = List.copyOf(expectedPages);
            relevantChunkIds = List.copyOf(relevantChunkIds);
        }
    }
}
