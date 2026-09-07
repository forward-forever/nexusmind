package com.wude.nexusmind.rag.api;

import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.rag.index.DocumentIndexingService;
import com.wude.nexusmind.rag.retrieval.DenseRetrievalService;
import com.wude.nexusmind.rag.retrieval.DenseSearchResult;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
public class DenseRetrievalController {

    private final DocumentIndexingService indexingService;
    private final DenseRetrievalService retrievalService;

    public DenseRetrievalController(DocumentIndexingService indexingService,
                                    DenseRetrievalService retrievalService) {
        this.indexingService = indexingService;
        this.retrievalService = retrievalService;
    }

    @PostMapping("/documents/{documentId}/index")
    public KnowledgeDocument index(@PathVariable long documentId) {
        return indexingService.index(documentId);
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/search")
    public DenseSearchResult search(@PathVariable long knowledgeBaseId,
                                    @Valid @RequestBody DenseSearchRequest request) {
        int topK = request.topK() == null ? DenseRetrievalService.DEFAULT_TOP_K : request.topK();
        return retrievalService.search(knowledgeBaseId, request.query(), topK);
    }
}
