package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentIngestionService;
import com.wude.nexusmind.knowledge.service.DocumentProcessingService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentControllerTest {

    @Test
    void listsDocumentSummariesForKnowledgeBase() {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(42L);
        document.setOriginalFileName("mysql.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(2048L);
        document.setStatus(DocumentStatus.READY);
        document.setChunkCount(7);
        document.setIndexStatus(DocumentIndexStatus.INDEXED);
        document.setIndexedAt(LocalDateTime.of(2026, 9, 8, 10, 0));
        document.setCreatedAt(LocalDateTime.of(2026, 9, 8, 9, 0));
        when(documentService.listByKnowledgeBase(9L)).thenReturn(List.of(document));

        DocumentController controller = new DocumentController(
                mock(DocumentIngestionService.class),
                mock(DocumentProcessingService.class),
                documentService,
                mock(ChunkService.class));

        List<DocumentSummaryResponse> result = controller.list(9L);

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(42L);
            assertThat(summary.originalFileName()).isEqualTo("mysql.pdf");
            assertThat(summary.status()).isEqualTo(DocumentStatus.READY);
            assertThat(summary.indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
            assertThat(summary.chunkCount()).isEqualTo(7);
        });
        verify(documentService).listByKnowledgeBase(9L);
    }
}
