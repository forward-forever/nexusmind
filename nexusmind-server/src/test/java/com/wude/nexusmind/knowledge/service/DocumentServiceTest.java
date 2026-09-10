package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.mapper.KnowledgeBaseMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceTest {

    @Test
    void insertsOneThousandTwoHundredOneChunksAsFiveHundredFiveHundredTwoHundredOne() {
        Fixture fixture = fixture();
        List<KnowledgeChunk> chunks = chunks(1_201);

        fixture.service.replaceChunksAndMarkReady(10L, chunks);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> batches = ArgumentCaptor.forClass(List.class);
        verify(fixture.chunkMapper, org.mockito.Mockito.times(3)).batchInsert(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(500, 500, 201);
        assertThat(batches.getAllValues().stream().flatMap(List::stream).map(KnowledgeChunk::getChunkIndex))
                .containsExactlyElementsOf(chunks.stream().map(KnowledgeChunk::getChunkIndex).toList());
        verify(fixture.chunkMapper).deleteByDocumentId(10L);
        verify(fixture.documentMapper).updateStatus(10L, DocumentStatus.READY, 1_201, null);
    }

    @Test
    void doesNotMarkDocumentReadyWhenAnIntermediateBatchFails() {
        Fixture fixture = fixture();
        RuntimeException failure = new RuntimeException("second batch failed");
        when(fixture.chunkMapper.batchInsert(anyList())).thenReturn(500).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.replaceChunksAndMarkReady(10L, chunks(1_201)))
                .isSameAs(failure);

        verify(fixture.chunkMapper).deleteByDocumentId(10L);
        verify(fixture.chunkMapper, org.mockito.Mockito.times(2)).batchInsert(anyList());
        verify(fixture.documentMapper, never()).updateStatus(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    private static Fixture fixture() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(10L);
        document.setKnowledgeBaseId(1L);
        document.setStatus(DocumentStatus.PROCESSING);
        doReturn(Optional.of(document)).when(documentMapper).findByIdForUpdate(10L);
        when(documentMapper.updateStatus(10L, DocumentStatus.READY, 1_201, null)).thenReturn(1);
        return new Fixture(
                new DocumentService(knowledgeBaseMapper, documentMapper, chunkMapper),
                documentMapper,
                chunkMapper);
    }

    private static List<KnowledgeChunk> chunks(int count) {
        List<KnowledgeChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String content = "chunk-" + index;
            chunks.add(new KnowledgeChunk(1L, 10L, index, content, null, null, content.length(), null));
        }
        return chunks;
    }

    private record Fixture(
            DocumentService service,
            KnowledgeDocumentMapper documentMapper,
            KnowledgeChunkMapper chunkMapper) {
    }
}
