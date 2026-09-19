package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCitationValidatorTest {

    private final AgentCitationValidator validator = new AgentCitationValidator();

    @Test
    void acceptsRegisteredCitations() {
        AgentSourceRegistry registry = registryWithTwoSources();

        assertThat(validator.invalidCitations("Answer [S1][S2]", registry)).isEmpty();
    }

    @Test
    void reportsOnlyUnknownRunScopedCitations() {
        AgentSourceRegistry registry = registryWithTwoSources();

        assertThat(validator.invalidCitations("Answer [S1] and [S99]", registry))
                .containsExactly("S99");
    }

    @Test
    void plainTextHasNoInvalidCitations() {
        assertThat(validator.invalidCitations("plain answer", new AgentSourceRegistry())).isEmpty();
    }

    private static AgentSourceRegistry registryWithTwoSources() {
        AgentSourceRegistry registry = new AgentSourceRegistry();
        registry.register(hit(10));
        registry.register(hit(20));
        return registry;
    }

    private static RetrievalHit hit(long chunkId) {
        return new RetrievalHit(chunkId, 1, "doc.pdf", 0, 0.8,
                RetrievalScoreType.COSINE, "content", 1, null);
    }
}
