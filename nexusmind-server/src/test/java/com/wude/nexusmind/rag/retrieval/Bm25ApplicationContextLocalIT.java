package com.wude.nexusmind.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "nexusmind.vector.enabled=true",
                "nexusmind.milvus.enabled=true",
                "nexusmind.rag.enabled=false"
        }
)
@ActiveProfiles("local")
class Bm25ApplicationContextLocalIT {

    @Autowired
    private RetrievalServiceRegistry registry;

    @Test
    void startsBm25WithoutCreatingDenseRetrieverOrEmbeddingModelDependency() {
        assertThat(registry.get(RetrieverType.BM25)).isInstanceOf(Bm25RetrievalService.class);
        assertThatThrownBy(() -> registry.get(RetrieverType.DENSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }
}
