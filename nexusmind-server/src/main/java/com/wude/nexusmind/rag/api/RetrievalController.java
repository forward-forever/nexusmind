package com.wude.nexusmind.rag.api;

import com.wude.nexusmind.rag.retrieval.RetrievalLimits;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
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
@ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "openai")
public class RetrievalController {

    private final RetrievalServiceRegistry retrievalServices;

    public RetrievalController(RetrievalServiceRegistry retrievalServices) {
        this.retrievalServices = retrievalServices;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/search")
    public RetrievalSearchResult search(@PathVariable long knowledgeBaseId,
                                        @Valid @RequestBody RetrievalSearchRequest request) {
        int topK = request.topK() == null ? RetrievalLimits.DEFAULT_TOP_K : request.topK();
        RetrieverType retrieverType = request.retrieverType() == null
                ? RetrieverType.DENSE
                : request.retrieverType();
        return RetrievalSearchResult.from(retrievalServices.get(retrieverType)
                .retrieve(knowledgeBaseId, request.query(), topK));
    }
}
