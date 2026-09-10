package com.wude.nexusmind.rag.index;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.model.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingBatchPlannerTest {

    @Test
    void keepsFifteenShortChunksInOneBatch() {
        List<KnowledgeChunk> chunks = chunks(15, 100);

        List<List<KnowledgeChunk>> batches = planner(15, 7_500).plan(chunks);

        assertThat(batches).extracting(List::size).containsExactly(15);
    }

    @Test
    void splitsBeforeCharacterLimitEvenWhenCountLimitIsNotReached() {
        List<KnowledgeChunk> chunks = chunks(8, 1_000);

        List<List<KnowledgeChunk>> batches = planner(15, 7_500).plan(chunks);

        assertThat(batches).extracting(List::size).containsExactly(7, 1);
    }

    @Test
    void appliesCountAndCharacterLimitsWithoutLosingOrder() {
        List<KnowledgeChunk> chunks = List.of(
                chunk(0, "a".repeat(4)), chunk(1, "b".repeat(4)), chunk(2, "c".repeat(4)),
                chunk(3, "d"), chunk(4, "e"), chunk(5, "f"), chunk(6, "g"));

        List<List<KnowledgeChunk>> batches = planner(3, 10).plan(chunks);

        assertThat(batches).extracting(List::size).containsExactly(2, 3, 2);
        assertThat(batches.stream().flatMap(List::stream).map(KnowledgeChunk::getChunkIndex))
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
    }

    @Test
    void allowsAChunkAtOrAboveTheCharacterLimitAsItsOwnBatch() {
        List<KnowledgeChunk> chunks = List.of(
                chunk(0, "a".repeat(7_500)),
                chunk(1, "b".repeat(7_501)),
                chunk(2, "short"));

        List<List<KnowledgeChunk>> batches = planner(15, 7_500).plan(chunks);

        assertThat(batches).extracting(List::size).containsExactly(1, 1, 1);
        assertThat(batches.stream().flatMap(List::stream).map(KnowledgeChunk::getChunkIndex))
                .containsExactly(0, 1, 2);
    }

    private static EmbeddingBatchPlanner planner(int batchSize, int maxBatchChars) {
        return new EmbeddingBatchPlanner(new EmbeddingProperties(
                "qwen3.7-text-embedding-flash", 4, batchSize, maxBatchChars));
    }

    private static List<KnowledgeChunk> chunks(int count, int chars) {
        List<KnowledgeChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            chunks.add(chunk(index, String.valueOf((char) ('a' + index % 26)).repeat(chars)));
        }
        return chunks;
    }

    private static KnowledgeChunk chunk(int index, String content) {
        return new KnowledgeChunk(1L, 10L, index, content, null, null, content.length(), null);
    }
}
