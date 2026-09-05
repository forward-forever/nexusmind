package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-bases")
@ConditionalOnProperty(name = "spring.datasource.url")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                request.name(),
                request.description(),
                request.embeddingModel(),
                request.embeddingDimension(),
                KnowledgeBaseStatus.ACTIVE
        );
        long id = knowledgeBaseService.create(knowledgeBase);
        return knowledgeBaseService.get(id);
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return knowledgeBaseService.list();
    }

    @GetMapping("/{id}")
    public KnowledgeBase get(@PathVariable long id) {
        return knowledgeBaseService.get(id);
    }
}
