package com.wude.nexusmind.rag.evaluation;

import java.util.List;

public record BenchmarkSourceCase(
        String id,
        String question,
        QueryCategory category,
        List<Integer> expectedPages,
        String expectedConcept,
        String note) {

    public BenchmarkSourceCase {
        expectedPages = expectedPages == null ? null : List.copyOf(expectedPages);
    }
}
