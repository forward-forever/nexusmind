package com.wude.nexusmind.rag.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RetrievalMetricsCalculator {

    public static final List<Integer> BASELINE_K_VALUES = List.of(1, 3, 5, 10);

    public RetrievalMetrics calculate(List<RetrievalEvaluationCaseResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("At least one evaluation result is required");
        }
        Map<Integer, RetrievalMetrics.AtK> metrics = new LinkedHashMap<>();
        for (int k : BASELINE_K_VALUES) {
            double hits = 0;
            double recall = 0;
            double reciprocalRanks = 0;
            for (RetrievalEvaluationCaseResult result : results) {
                long hitsAtK = result.hitRanks().stream().filter(rank -> rank <= k).count();
                if (hitsAtK > 0) {
                    hits++;
                }
                recall += (double) hitsAtK / result.relevantChunkIds().size();
                Integer firstRank = result.firstRelevantRank();
                if (firstRank != null && firstRank <= k) {
                    reciprocalRanks += 1.0 / firstRank;
                }
            }
            int queryCount = results.size();
            metrics.put(k, new RetrievalMetrics.AtK(
                    k,
                    hits / queryCount,
                    recall / queryCount,
                    reciprocalRanks / queryCount));
        }
        return new RetrievalMetrics(metrics);
    }

    public RetrievalLatencyMetrics latency(List<RetrievalEvaluationCaseResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("At least one evaluation result is required");
        }
        long[] sorted = results.stream().mapToLong(RetrievalEvaluationCaseResult::latencyMs).sorted().toArray();
        double average = Arrays.stream(sorted).average().orElseThrow();
        return new RetrievalLatencyMetrics(
                average,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                sorted[sorted.length - 1]);
    }

    public List<RetrievalCategoryBreakdown> categoryBreakdown(
            List<RetrievalEvaluationCaseResult> results) {
        List<RetrievalCategoryBreakdown> breakdown = new ArrayList<>();
        for (QueryCategory category : QueryCategory.values()) {
            List<RetrievalEvaluationCaseResult> categoryResults = results.stream()
                    .filter(result -> result.category() == category)
                    .toList();
            if (categoryResults.isEmpty()) {
                continue;
            }
            RetrievalMetrics.AtK at5 = calculate(categoryResults).at(5);
            breakdown.add(new RetrievalCategoryBreakdown(
                    category, categoryResults.size(), at5.hitRate(), at5.mrr()));
        }
        return List.copyOf(breakdown);
    }

    private static long percentile(long[] sorted, double percentile) {
        int nearestRank = (int) Math.ceil(percentile * sorted.length);
        return sorted[Math.max(0, nearestRank - 1)];
    }
}
