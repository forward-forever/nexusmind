package com.wude.nexusmind.rag.embedding;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.model.config.EmbeddingProperties;
import com.wude.nexusmind.rag.exception.EmbeddingConfigurationMismatchException;
import com.wude.nexusmind.rag.exception.EmbeddingGenerationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingBatchServiceTest {

    @Test
    void sendsTwentyInputsInOneEmbeddingModelCall() {
        StubEmbeddingModel model = new StubEmbeddingModel(4);
        EmbeddingBatchService service = service(model, 20);
        List<String> inputs = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> "text-" + index)
                .toList();

        List<float[]> vectors = service.embedBatch(inputs, 4, 1);

        assertThat(vectors).hasSize(20);
        assertThat(model.batchSizes).containsExactly(20);
    }

    @Test
    void rejectsBatchLargerThanProviderLimit() {
        EmbeddingBatchService service = service(new StubEmbeddingModel(4), 20);
        List<String> inputs = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "text-" + index)
                .toList();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.embedBatch(inputs, 4, 1))
                .withMessageContaining("exceeds");
    }

    @Test
    void rejectsMultiItemBatchOverConfiguredCharacterLimit() {
        EmbeddingBatchService service = new EmbeddingBatchService(
                new StubEmbeddingModel(4),
                new EmbeddingProperties("qwen3.7-text-embedding-flash", 4, 15, 10));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.embedBatch(List.of("123456", "12345"), 4, 1))
                .withMessageContaining("characters 11")
                .withMessageContaining("maximum 10");
    }

    @Test
    void allowsSingleOversizedItemSoThePlannerCannotDeadlock() {
        EmbeddingBatchService service = new EmbeddingBatchService(
                new StubEmbeddingModel(4),
                new EmbeddingProperties("qwen3.7-text-embedding-flash", 4, 15, 10));

        assertThat(service.embedBatch(List.of("x".repeat(11)), 4, 1)).hasSize(1);
    }

    @Test
    void rejectsWrongVectorCount() {
        StubEmbeddingModel model = new StubEmbeddingModel(4);
        model.dropLastVector = true;

        assertThatThrownBy(() -> service(model, 20).embedBatch(List.of("one", "two"), 4, 1))
                .isInstanceOf(EmbeddingGenerationException.class)
                .hasMessageContaining("1 vectors for 2 inputs");
    }

    @Test
    void rejectsWrongVectorDimension() {
        assertThatThrownBy(() -> service(new StubEmbeddingModel(3), 20)
                .embedBatch(List.of("one"), 4, 1))
                .isInstanceOf(EmbeddingGenerationException.class)
                .hasMessageContaining("dimension 3, expected 4");
    }

    @Test
    void wrapsEmbeddingProviderFailureWithoutLoggingContentOrVector() {
        StubEmbeddingModel model = new StubEmbeddingModel(4);
        model.failure = new IllegalStateException("provider unavailable");

        assertThatThrownBy(() -> service(model, 20).embedBatch(List.of("private content"), 4, 3))
                .isInstanceOf(EmbeddingGenerationException.class)
                .hasMessage("Embedding API failed for batch 3 (size=1)")
                .hasCause(model.failure);
    }

    @Test
    void rejectsKnowledgeBaseWhoseFrozenEmbeddingConfigDiffersFromRuntime() {
        EmbeddingBatchService service = service(new StubEmbeddingModel(4), 20);
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                "legacy",
                null,
                "planned-model",
                1536,
                KnowledgeBaseStatus.ACTIVE);

        assertThatThrownBy(() -> service.validateCompatibility(knowledgeBase))
                .isInstanceOf(EmbeddingConfigurationMismatchException.class)
                .hasMessageContaining("does not match runtime model");
    }

    private static EmbeddingBatchService service(EmbeddingModel model, int batchSize) {
        return new EmbeddingBatchService(
                model,
                new EmbeddingProperties("qwen3.7-text-embedding-flash", 4, batchSize, 7_500));
    }

    static final class StubEmbeddingModel implements EmbeddingModel {

        private final int dimension;
        private final List<Integer> batchSizes = new ArrayList<>();
        private boolean dropLastVector;
        private RuntimeException failure;

        StubEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            batchSizes.add(texts.size());
            if (failure != null) {
                throw failure;
            }
            int count = dropLastVector ? Math.max(0, texts.size() - 1) : texts.size();
            List<float[]> vectors = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                float[] vector = new float[dimension];
                vector[index % dimension] = 1.0f;
                vectors.add(vector);
            }
            return vectors;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("Test stub uses batch embed directly");
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException("Test stub uses batch embed directly");
        }
    }
}
