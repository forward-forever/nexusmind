package com.wude.nexusmind.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceRegistryTest {

    @Test
    void selectsDenseAndBm25WithoutFallback() {
        RetrievalService dense = mock(RetrievalService.class);
        RetrievalService bm25 = mock(RetrievalService.class);
        when(dense.type()).thenReturn(RetrieverType.DENSE);
        when(bm25.type()).thenReturn(RetrieverType.BM25);
        RetrievalServiceRegistry registry = new RetrievalServiceRegistry(List.of(dense, bm25));

        assertThat(registry.get(RetrieverType.DENSE)).isSameAs(dense);
        assertThat(registry.get(RetrieverType.BM25)).isSameAs(bm25);
        assertThatThrownBy(() -> registry.get(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        RetrievalServiceRegistry incomplete = new RetrievalServiceRegistry(List.of(dense));
        assertThatThrownBy(() -> incomplete.get(RetrieverType.BM25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void rejectsDuplicateRetrieverType() {
        RetrievalService first = mock(RetrievalService.class);
        RetrievalService second = mock(RetrievalService.class);
        when(first.type()).thenReturn(RetrieverType.DENSE);
        when(second.type()).thenReturn(RetrieverType.DENSE);

        assertThatThrownBy(() -> new RetrievalServiceRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }
}
