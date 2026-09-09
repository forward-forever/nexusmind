package com.wude.nexusmind.knowledge;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.DocumentParsingException;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentStateException;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentIngestionService;
import com.wude.nexusmind.knowledge.service.DocumentProcessingService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.DocumentUploadResult;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.knowledge.storage.DocumentStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "nexusmind.vector.enabled=false",
                "nexusmind.milvus.enabled=false",
                "nexusmind.rag.enabled=false"
        }
)
@ActiveProfiles("local")
class DocumentIngestionLocalIT {

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void ingestionProperties(DynamicPropertyRegistry registry) {
        registry.add("nexusmind.storage.root", () -> STORAGE_ROOT.toString());
        registry.add("nexusmind.storage.max-file-size", () -> "1MB");
        registry.add("nexusmind.chunking.chunk-size-chars", () -> "60");
        registry.add("nexusmind.chunking.chunk-overlap-chars", () -> "10");
    }

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentProcessingService processingService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ChunkService chunkService;

    @Autowired
    private DocumentStorage documentStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void txtMarkdownAndPdfFlowThroughStorageParserChunkerAndMysql() throws Exception {
        Long knowledgeBaseId = null;
        try {
            knowledgeBaseId = knowledgeBaseService.create(
                    "checkpoint-3-integration",
                    "Document ingestion integration test"
            );

            byte[] txtBytes = ("NexusMind TXT 第一段。This text is long enough to demonstrate character chunking. "
                    + "第二段继续描述 upload storage parser chunk and MySQL persistence.")
                    .getBytes(StandardCharsets.UTF_8);
            DocumentUploadResult txtUpload = ingestionService.upload(knowledgeBaseId,
                    multipart("notes.txt", "text/plain", txtBytes));
            assertThat(txtUpload.duplicate()).isFalse();
            assertThat(txtUpload.document().getStatus()).isEqualTo(DocumentStatus.UPLOADED);
            assertThat(txtUpload.document().getStoragePath()).startsWith("knowledge/" + knowledgeBaseId + "/");
            assertThat(Path.of(txtUpload.document().getStoragePath())).isRelative();
            assertThat(documentStorage.resolve(txtUpload.document().getStoragePath())).isRegularFile();

            KnowledgeDocument readyTxt = processingService.process(txtUpload.document().getId());
            List<KnowledgeChunk> txtChunks = chunkService.listByDocument(readyTxt.getId());
            assertReadyWithChunks(readyTxt, txtChunks);
            assertThat(txtChunks).allSatisfy(chunk -> {
                assertThat(chunk.getPageNo()).isNull();
                assertThat(chunk.getSectionTitle()).isNull();
            });

            DocumentUploadResult duplicateTxt = ingestionService.upload(knowledgeBaseId,
                    multipart("notes.txt", "text/plain", txtBytes));
            assertThat(duplicateTxt.duplicate()).isTrue();
            assertThat(duplicateTxt.document().getId()).isEqualTo(txtUpload.document().getId());

            byte[] markdownBytes = """
                    # Architecture
                    Upload is stored before processing.
                    ```java
                    # not a heading inside code
                    System.out.println("NexusMind");
                    ```
                    ## Transactions
                    Chunk replacement is committed in one short transaction.
                    """.getBytes(StandardCharsets.UTF_8);
            DocumentUploadResult markdownUpload = ingestionService.upload(knowledgeBaseId,
                    multipart("architecture.md", "text/markdown", markdownBytes));
            KnowledgeDocument readyMarkdown = processingService.process(markdownUpload.document().getId());
            List<KnowledgeChunk> markdownChunks = chunkService.listByDocument(readyMarkdown.getId());
            assertReadyWithChunks(readyMarkdown, markdownChunks);
            assertThat(markdownChunks).extracting(KnowledgeChunk::getSectionTitle)
                    .contains("Architecture", "Transactions")
                    .doesNotContain("not a heading inside code");

            DocumentUploadResult pdfUpload = ingestionService.upload(knowledgeBaseId,
                    multipart("citation.pdf", "application/pdf", twoPagePdf()));
            KnowledgeDocument readyPdf = processingService.process(pdfUpload.document().getId());
            List<KnowledgeChunk> pdfChunks = chunkService.listByDocument(readyPdf.getId());
            assertReadyWithChunks(readyPdf, pdfChunks);
            assertThat(pdfChunks).extracting(KnowledgeChunk::getPageNo).contains(1, 2);
            assertThat(pdfChunks).extracting(KnowledgeChunk::getSectionTitle).containsOnlyNulls();

            DocumentUploadResult invalidText = ingestionService.upload(knowledgeBaseId,
                    multipart("blank.txt", "text/plain", "   \n\t".getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> processingService.process(invalidText.document().getId()))
                    .isInstanceOf(DocumentParsingException.class);
            KnowledgeDocument failedDocument = documentService.get(invalidText.document().getId());
            assertThat(failedDocument.getStatus()).isEqualTo(DocumentStatus.FAILED);
            assertThat(failedDocument.getErrorMessage()).contains("usable text");

            assertThatThrownBy(() -> processingService.process(readyTxt.getId()))
                    .isInstanceOf(InvalidDocumentStateException.class);

            assertConcurrentDuplicateUpload(knowledgeBaseId);
        } finally {
            if (knowledgeBaseId != null) {
                jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE knowledge_base_id = ?", knowledgeBaseId);
                jdbcTemplate.update("DELETE FROM knowledge_document WHERE knowledge_base_id = ?", knowledgeBaseId);
                jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
            }
            deleteStorageTree();
        }
    }

    private void assertConcurrentDuplicateUpload(long knowledgeBaseId) throws Exception {
        byte[] bytes = "concurrent duplicate upload".getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DocumentUploadResult> first = executor.submit(() -> {
                start.await();
                return ingestionService.upload(knowledgeBaseId,
                        multipart("concurrent.txt", "text/plain", bytes));
            });
            Future<DocumentUploadResult> second = executor.submit(() -> {
                start.await();
                return ingestionService.upload(knowledgeBaseId,
                        multipart("concurrent.txt", "text/plain", bytes));
            });
            start.countDown();

            DocumentUploadResult firstResult = first.get();
            DocumentUploadResult secondResult = second.get();
            assertThat(firstResult.document().getId()).isEqualTo(secondResult.document().getId());
            assertThat(List.of(firstResult.duplicate(), secondResult.duplicate())).contains(true);
            assertThat(documentService.listByKnowledgeBase(knowledgeBaseId).stream()
                    .filter(document -> document.getFileSha256().equals(firstResult.document().getFileSha256())))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertReadyWithChunks(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(document.getChunkCount()).isPositive().isEqualTo(chunks.size());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).extracting(KnowledgeChunk::getChunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getTokenCount()).isNull());
    }

    private static MockMultipartFile multipart(String fileName, String contentType, byte[] content) {
        return new MockMultipartFile("file", fileName, contentType, content);
    }

    private static byte[] twoPagePdf() throws IOException {
        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addPage(pdf, "PDF page one contains citation metadata.");
            addPage(pdf, "PDF page two keeps a different page number.");
            pdf.save(output);
            return output.toByteArray();
        }
    }

    private static void addPage(PDDocument pdf, String text) throws IOException {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("nexusmind-ingestion-it-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void deleteStorageTree() {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary test storage is best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Temporary test storage is best-effort cleanup.
        }
    }
}
