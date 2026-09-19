package com.wude.nexusmind.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=openai",
                "spring.autoconfigure.exclude="
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
                "nexusmind.vector.enabled=true",
                "nexusmind.document-task.worker-enabled=false",
                "nexusmind.milvus.enabled=true",
                "nexusmind.rag.enabled=false"
        }
)
@ActiveProfiles("local")
@Import(Bm25ApplicationContextLocalIT.FakeEmbeddingConfiguration.class)
class Bm25ApplicationContextLocalIT {

    @Autowired
    private RetrievalServiceRegistry registry;

    @Test
    void registersDenseBm25AndHybridWithoutCallingExternalEmbedding() {
        assertThat(registry.get(RetrieverType.DENSE).type()).isEqualTo(RetrieverType.DENSE);
        assertThat(registry.get(RetrieverType.BM25).type()).isEqualTo(RetrieverType.BM25);
        assertThat(registry.get(RetrieverType.HYBRID_RRF).type()).isEqualTo(RetrieverType.HYBRID_RRF);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEmbeddingConfiguration {

        @Bean
        @Primary
        EmbeddingModel fakeEmbeddingModel() {
            return new EmbeddingModel() {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(ignored -> new float[1024]).toList();
                }

                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    throw new UnsupportedOperationException("Offline test does not call the provider");
                }

                @Override
                public float[] embed(Document document) {
                    throw new UnsupportedOperationException("Offline test does not call the provider");
                }
            };
        }
    }
}
