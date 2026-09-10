package com.wude.nexusmind.rag.embedding;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.model.config.EmbeddingProperties;
import com.wude.nexusmind.rag.exception.EmbeddingConfigurationMismatchException;
import com.wude.nexusmind.rag.exception.EmbeddingGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Duration;
import java.util.List;

public class EmbeddingBatchService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties properties;

    public EmbeddingBatchService(EmbeddingModel embeddingModel, EmbeddingProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    public void validateCompatibility(KnowledgeBase knowledgeBase) {
        if (!properties.model().equals(knowledgeBase.getEmbeddingModel())) {
            throw new EmbeddingConfigurationMismatchException(
                    "Knowledge base embedding model '%s' does not match runtime model '%s'"
                            .formatted(knowledgeBase.getEmbeddingModel(), properties.model()));
        }
        if (!Integer.valueOf(properties.dimension()).equals(knowledgeBase.getEmbeddingDimension())) {
            throw new EmbeddingConfigurationMismatchException(
                    "Knowledge base embedding dimension %s does not match runtime dimension %d"
                            .formatted(knowledgeBase.getEmbeddingDimension(), properties.dimension()));
        }
    }

    public List<float[]> embedBatch(List<String> texts, int expectedDimension, int batchNumber) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Embedding batch cannot be empty");
        }
        if (texts.size() > properties.batchSize()) {
            throw new IllegalArgumentException(
                    "Embedding batch size %d exceeds configured maximum %d"
                            .formatted(texts.size(), properties.batchSize()));
        }
        int batchChars = texts.stream().mapToInt(String::length).sum();
        if (texts.size() > 1 && batchChars > properties.maxBatchChars()) {
            throw new IllegalArgumentException(
                    "Embedding batch characters %d exceed configured maximum %d"
                            .formatted(batchChars, properties.maxBatchChars()));
        }
        long startedAt = System.nanoTime();
        try {
            List<float[]> vectors = embeddingModel.embed(List.copyOf(texts));
            validateVectors(vectors, texts.size(), expectedDimension);
            log.info("Embedding batch completed: batch={}, size={}, chars={}, latencyMs={}, model={}, dimension={}",
                    batchNumber,
                    texts.size(),
                    batchChars,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    properties.model(),
                    expectedDimension);
            return List.copyOf(vectors);
        } catch (EmbeddingGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EmbeddingGenerationException(
                    "Embedding API failed for batch %d (size=%d)".formatted(batchNumber, texts.size()),
                    exception);
        }
    }

    public float[] embedQuery(String query, int expectedDimension) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query is required");
        }
        return embedBatch(List.of(query), expectedDimension, 1).get(0);
    }

    public int batchSize() {
        return properties.batchSize();
    }

    public int maxBatchChars() {
        return properties.maxBatchChars();
    }

    public String model() {
        return properties.model();
    }

    public int dimension() {
        return properties.dimension();
    }

    private static void validateVectors(List<float[]> vectors, int expectedCount, int expectedDimension) {
        if (vectors == null || vectors.size() != expectedCount) {
            int actualCount = vectors == null ? 0 : vectors.size();
            throw new EmbeddingGenerationException(
                    "Embedding API returned %d vectors for %d inputs".formatted(actualCount, expectedCount));
        }
        for (int index = 0; index < vectors.size(); index++) {
            float[] vector = vectors.get(index);
            if (vector == null || vector.length != expectedDimension) {
                int actualDimension = vector == null ? 0 : vector.length;
                throw new EmbeddingGenerationException(
                        "Embedding vector %d has dimension %d, expected %d"
                                .formatted(index, actualDimension, expectedDimension));
            }
        }
    }
}
