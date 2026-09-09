package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentIngestionService;
import com.wude.nexusmind.knowledge.service.DocumentProcessingService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.DocumentUploadResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "spring.datasource.url")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final DocumentProcessingService processingService;
    private final DocumentService documentService;
    private final ChunkService chunkService;

    public DocumentController(DocumentIngestionService ingestionService,
                              DocumentProcessingService processingService,
                              DocumentService documentService,
                              ChunkService chunkService) {
        this.ingestionService = ingestionService;
        this.processingService = processingService;
        this.documentService = documentService;
        this.chunkService = chunkService;
    }

    @PostMapping(
            path = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<UploadDocumentResponse> upload(@PathVariable long knowledgeBaseId,
                                                         @RequestPart("file") MultipartFile file) {
        DocumentUploadResult result = ingestionService.upload(knowledgeBaseId, file);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(new UploadDocumentResponse(result.document(), result.duplicate()));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public List<DocumentSummaryResponse> list(@PathVariable long knowledgeBaseId) {
        return documentService.listByKnowledgeBase(knowledgeBaseId).stream()
                .map(DocumentSummaryResponse::from)
                .toList();
    }

    @PostMapping("/documents/{documentId}/process")
    public KnowledgeDocument process(@PathVariable long documentId) {
        return processingService.process(documentId);
    }

    @GetMapping("/documents/{documentId}")
    public KnowledgeDocument get(@PathVariable long documentId) {
        return documentService.get(documentId);
    }

    @GetMapping("/documents/{documentId}/chunks")
    public List<KnowledgeChunk> chunks(@PathVariable long documentId) {
        return chunkService.listByDocument(documentId);
    }
}
