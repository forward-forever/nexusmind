package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.rag.rerank.RerankClient;
import com.wude.nexusmind.rag.rerank.RerankResult;
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
                "nexusmind.rag.enabled=false",
                "nexusmind.ai.rerank.enabled=true",
                "nexusmind.ai.rerank.base-url=https://workspace.example/api/v1",
                "nexusmind.ai.rerank.api-key=fake-test-key"
        }
)
@ActiveProfiles("local")
@Import(HybridRerankApplicationContextLocalIT.FakeModelConfiguration.class)
class HybridRerankApplicationContextLocalIT {

    @Autowired
    private RetrievalServiceRegistry registry;

    @Test
    void registersHybridRerankWithFakeExternalModels() {
        assertThat(registry.get(RetrieverType.HYBRID_RERANK))
                .extracting(RetrievalService::type)
                .isEqualTo(RetrieverType.HYBRID_RERANK);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfiguration {

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

        @Bean
        @Primary
        RerankClient fakeRerankClient() {
            return (query, documents, topN) -> new RerankResult(
                    List.of(new RerankResult.Item(0, 1.0)), null, 1);
        }
    }
}
