package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSourceRegistryTest {

    @Test
    void reusesIdsForTheSameChunkAndAssignsContinuousIds() {
        AgentSourceRegistry registry = new AgentSourceRegistry();

        assertThat(registry.register(hit(100L)).sourceId()).isEqualTo("S1");
        assertThat(registry.register(hit(100L)).sourceId()).isEqualTo("S1");
        assertThat(registry.register(hit(200L)).sourceId()).isEqualTo("S2");
        assertThat(registry.snapshot()).extracting(source -> source.chunkId())
                .containsExactly(100L, 200L);
    }

    private static RetrievalHit hit(long chunkId) {
        return new RetrievalHit(chunkId, 10L, "doc.pdf", 0, 0.9,
                RetrievalScoreType.COSINE, "content", 1, null);
    }
}
