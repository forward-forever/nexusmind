package com.wude.nexusmind.knowledge.chunk;

import com.wude.nexusmind.knowledge.config.ChunkingProperties;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.parser.ParsedDocument;
import com.wude.nexusmind.knowledge.parser.ParsedSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SlidingWindowChunkerTest {

    @Test
    void emptyTextProducesNoChunks() {
        assertThat(chunker(10, 2).chunk(document("   "), 1, 2)).isEmpty();
    }

    @Test
    void textShorterThanChunkSizeProducesOneChunk() {
        List<KnowledgeChunk> chunks = chunker(10, 2).chunk(document("short"), 1, 2);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).isEqualTo("short");
        assertThat(chunks.get(0).getCharCount()).isEqualTo(5);
    }

    @Test
    void textEqualToChunkSizeProducesOneChunk() {
        assertThat(chunker(10, 2).chunk(document("0123456789"), 1, 2))
                .extracting(KnowledgeChunk::getContent)
                .containsExactly("0123456789");
    }

    @Test
    void textLongerThanChunkSizeProducesMultipleChunks() {
        assertThat(chunker(10, 0).chunk(document("abcdefghijklmnopqrstuv"), 1, 2))
                .extracting(KnowledgeChunk::getContent)
                .containsExactly("abcdefghij", "klmnopqrst", "uv");
    }

    @Test
    void configuredOverlapIsPresentInAdjacentChunks() {
        List<KnowledgeChunk> chunks = chunker(10, 3)
                .chunk(document("abcdefghijklmnop"), 1, 2);

        assertThat(chunks).extracting(KnowledgeChunk::getContent)
                .containsExactly("abcdefghij", "hijklmnop");
        assertThat(chunks.get(0).getContent()).endsWith(chunks.get(1).getContent().substring(0, 3));
    }

    @Test
    void sentenceBoundaryIsPreferredNearTargetSize() {
        List<KnowledgeChunk> chunks = chunker(20, 0)
                .chunk(document("123456789012345。ABCDEFGHIJKLMN"), 1, 2);

        assertThat(chunks.get(0).getContent()).isEqualTo("123456789012345。");
    }

    @Test
    void textWithoutNaturalBoundaryUsesHardCut() {
        assertThat(chunker(8, 0).chunk(document("abcdefghijkl"), 1, 2))
                .extracting(KnowledgeChunk::getContent)
                .containsExactly("abcdefgh", "ijkl");
    }

    @Test
    void sectionTitleIsPropagated() {
        ParsedDocument document = new ParsedDocument(List.of(
                new ParsedSection("section content", null, "Architecture")
        ));

        assertThat(chunker(100, 10).chunk(document, 7, 8).get(0).getSectionTitle())
                .isEqualTo("Architecture");
    }

    @Test
    void pageNumberIsPropagated() {
        ParsedDocument document = new ParsedDocument(List.of(
                new ParsedSection("page content", 17, null)
        ));

        assertThat(chunker(100, 10).chunk(document, 7, 8).get(0).getPageNo())
                .isEqualTo(17);
    }

    @Test
    void chunkIndexesRemainContinuousAcrossSections() {
        ParsedDocument document = new ParsedDocument(List.of(
                new ParsedSection("abcdefghij12", 1, null),
                new ParsedSection("mnopqrstuv34", 2, null)
        ));

        List<KnowledgeChunk> chunks = chunker(10, 0).chunk(document, 7, 8);

        assertThat(chunks).extracting(KnowledgeChunk::getChunkIndex)
                .containsExactly(0, 1, 2, 3);
        assertThat(chunks.subList(0, 2)).extracting(KnowledgeChunk::getPageNo).containsOnly(1);
        assertThat(chunks.subList(2, 4)).extracting(KnowledgeChunk::getPageNo).containsOnly(2);
    }

    @Test
    void invalidChunkConfigurationIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingProperties(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingProperties(10, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingProperties(10, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingProperties(10, 11));
    }

    private static SlidingWindowChunker chunker(int size, int overlap) {
        return new SlidingWindowChunker(new ChunkingProperties(size, overlap));
    }

    private static ParsedDocument document(String text) {
        return new ParsedDocument(List.of(new ParsedSection(text, null, null)));
    }
}
