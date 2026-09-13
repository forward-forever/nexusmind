package com.wude.nexusmind.rag.evaluation;

public record RetrievalCategoryBreakdown(
        QueryCategory category,
        int queryCount,
        double hitRateAt5,
        double mrrAt5) {
}
