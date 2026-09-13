package com.wude.nexusmind.rag.evaluation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RetrievalMetricsCalculatorTest {

    private final RetrievalMetricsCalculator calculator = new RetrievalMetricsCalculator();

    @Test
    void calculatesHitRateMacroRecallAndMrrAtAllBaselineKValues() {
        List<RetrievalEvaluationCaseResult> results = List.of(
                result("q1", QueryCategory.EXACT, List.of(1L, 2L), List.of(1, 5), 1, 10),
                result("q2", QueryCategory.SEMANTIC, List.of(3L), List.of(3), 3, 20),
                result("q3", QueryCategory.SHORT, List.of(4L), List.of(), null, 30));

        RetrievalMetrics metrics = calculator.calculate(results);

        assertThat(metrics.values().keySet()).containsExactlyInAnyOrder(1, 3, 5, 10);
        assertThat(metrics.at(1).hitRate()).isCloseTo(1.0 / 3, within(0.000001));
        assertThat(metrics.at(1).recall()).isCloseTo(1.0 / 6, within(0.000001));
        assertThat(metrics.at(1).mrr()).isCloseTo(1.0 / 3, within(0.000001));
        assertThat(metrics.at(3).hitRate()).isCloseTo(2.0 / 3, within(0.000001));
        assertThat(metrics.at(3).recall()).isCloseTo(0.5, within(0.000001));
        assertThat(metrics.at(3).mrr()).isCloseTo(4.0 / 9, within(0.000001));
        assertThat(metrics.at(5).recall()).isCloseTo(2.0 / 3, within(0.000001));
        assertThat(metrics.at(10).mrr()).isCloseTo(4.0 / 9, within(0.000001));
    }

    @Test
    void usesOneBasedRankFiveAndZeroForNoHit() {
        RetrievalMetrics metrics = calculator.calculate(List.of(
                result("rank5", QueryCategory.OTHER, List.of(1L), List.of(5), 5, 1),
                result("miss", QueryCategory.OTHER, List.of(2L), List.of(), null, 1)));

        assertThat(metrics.at(3).hitRate()).isZero();
        assertThat(metrics.at(5).hitRate()).isEqualTo(0.5);
        assertThat(metrics.at(5).recall()).isEqualTo(0.5);
        assertThat(metrics.at(5).mrr()).isCloseTo(0.1, within(0.000001));
    }

    @Test
    void calculatesNearestRankLatencyPercentiles() {
        List<RetrievalEvaluationCaseResult> results = new ArrayList<>();
        for (int latency = 1; latency <= 20; latency++) {
            results.add(result("q" + latency, QueryCategory.EXACT,
                    List.of(1L), List.of(1), 1, latency));
        }

        RetrievalLatencyMetrics latency = calculator.latency(results);

        assertThat(latency.averageMs()).isEqualTo(10.5);
        assertThat(latency.p50Ms()).isEqualTo(10);
        assertThat(latency.p95Ms()).isEqualTo(19);
        assertThat(latency.maxMs()).isEqualTo(20);
    }

    @Test
    void aggregatesOnlyCategoriesThatHaveQueries() {
        List<RetrievalEvaluationCaseResult> results = List.of(
                result("q1", QueryCategory.EXACT, List.of(1L), List.of(1), 1, 1),
                result("q2", QueryCategory.EXACT, List.of(2L), List.of(), null, 1),
                result("q3", QueryCategory.SEMANTIC, List.of(3L), List.of(2), 2, 1));

        List<RetrievalCategoryBreakdown> breakdown = calculator.categoryBreakdown(results);

        assertThat(breakdown).extracting(RetrievalCategoryBreakdown::category)
                .containsExactly(QueryCategory.EXACT, QueryCategory.SEMANTIC);
        assertThat(breakdown.get(0).queryCount()).isEqualTo(2);
        assertThat(breakdown.get(0).hitRateAt5()).isEqualTo(0.5);
        assertThat(breakdown.get(1).mrrAt5()).isEqualTo(0.5);
    }

    private static RetrievalEvaluationCaseResult result(String id,
                                                        QueryCategory category,
                                                        List<Long> relevant,
                                                        List<Integer> hitRanks,
                                                        Integer firstRank,
                                                        long latency) {
        return new RetrievalEvaluationCaseResult(
                id, 1L, "question", category, relevant, List.of(), hitRanks, firstRank, latency);
    }
}
