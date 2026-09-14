package com.wude.nexusmind.rag.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridRouteCandidatePlannerTest {

    @Test
    void appliesDefaultMultiplierMinimumAndMaximum() {
        HybridRouteCandidatePlanner planner = new HybridRouteCandidatePlanner(properties());

        assertThat(planner.plan(1)).isEqualTo(20);
        assertThat(planner.plan(5)).isEqualTo(20);
        assertThat(planner.plan(10)).isEqualTo(40);
        assertThat(planner.plan(20)).isEqualTo(60);
    }

    @Test
    void rejectsInvalidFinalTopKAndConfiguration() {
        HybridRouteCandidatePlanner planner = new HybridRouteCandidatePlanner(properties());

        assertThatThrownBy(() -> planner.plan(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HybridRetrievalProperties(
                new HybridRetrievalProperties.Rrf(0), 4, 20, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HybridRetrievalProperties(
                new HybridRetrievalProperties.Rrf(60), 4, 5, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static HybridRetrievalProperties properties() {
        return new HybridRetrievalProperties(
                new HybridRetrievalProperties.Rrf(60), 4, 20, 60);
    }
}
