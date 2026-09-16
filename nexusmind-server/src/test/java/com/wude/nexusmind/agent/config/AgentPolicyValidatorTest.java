package com.wude.nexusmind.agent.config;

import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPolicyValidatorTest {

    @Test
    void rejectsKnowledgeSearchTopKAboveExistingRetrievalMaximum() {
        AgentProperties properties = new AgentProperties(
                true, 5, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 21),
                contextPolicy());

        assertThatThrownBy(() -> new AgentPolicyValidator(
                properties, registry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void rejectsRetrieverThatIsNotAvailableInTheRuntimeRegistry() {
        AgentProperties properties = new AgentProperties(
                true, 5, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.BM25, 5),
                contextPolicy());

        assertThatThrownBy(() -> new AgentPolicyValidator(
                properties, registry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported retriever type");
    }

    @Test
    void rejectsNonPositiveAbsoluteDuration() {
        assertThatThrownBy(() -> new AgentProperties(
                true, 5, Duration.ZERO,
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                contextPolicy()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duration must be positive");
    }

    private static RetrievalServiceRegistry registry() {
        RetrievalService dense = mock(RetrievalService.class);
        when(dense.type()).thenReturn(RetrieverType.DENSE);
        return new RetrievalServiceRegistry(List.of(dense));
    }

    private static AgentProperties.DocumentContext contextPolicy() {
        return new AgentProperties.DocumentContext(1, 1);
    }

}
