package com.wude.nexusmind.rag.evaluation;

import java.util.List;

public record RetrievalEvaluationDataset(String name, List<RetrievalEvaluationCase> cases) {

    public RetrievalEvaluationDataset {
        cases = List.copyOf(cases);
    }
}
