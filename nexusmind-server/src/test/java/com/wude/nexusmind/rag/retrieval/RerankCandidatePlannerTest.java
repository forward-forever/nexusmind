package com.wude.nexusmind.rag.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankCandidatePlannerTest {

    private final RerankCandidatePlanner planner =
            new RerankCandidatePlanner(new RerankRetrievalProperties(20, 50));

    @Test
    void appliesConfiguredCandidateBaselineAndExpandsForLargerFinalTopK() {
        assertThat(planner.plan(5)).isEqualTo(20);
        assertThat(planner.plan(10)).isEqualTo(20);
        assertThat(planner.plan(30)).isEqualTo(30);
    }

    @Test
    void rejectsInvalidOrUnsupportedFinalTopK() {
        assertThatThrownBy(() -> planner.plan(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(51))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
    }
}
