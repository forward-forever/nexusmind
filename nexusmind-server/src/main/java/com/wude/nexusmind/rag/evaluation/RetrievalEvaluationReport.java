package com.wude.nexusmind.rag.evaluation;

import java.util.List;

public record RetrievalEvaluationReport(
        RetrievalBaselineMetadata metadata,
        RetrievalMetrics metrics,
        RetrievalLatencyMetrics latency,
        List<RetrievalCategoryBreakdown> categoryBreakdown,
        List<RetrievalEvaluationCaseResult> cases,
        List<RetrievalEvaluationCaseResult> failureCases) {

    public RetrievalEvaluationReport {
        categoryBreakdown = List.copyOf(categoryBreakdown);
        cases = List.copyOf(cases);
        failureCases = List.copyOf(failureCases);
    }
}
