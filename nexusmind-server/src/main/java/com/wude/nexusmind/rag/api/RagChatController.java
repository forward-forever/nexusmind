package com.wude.nexusmind.rag.api;

import com.wude.nexusmind.rag.chat.RagChatService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/knowledge-bases")
@ConditionalOnProperty(name = "nexusmind.rag.enabled", havingValue = "true")
public class RagChatController {

    private final RagChatService ragChatService;

    public RagChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping(
            value = "/{knowledgeBaseId}/rag/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<RagStreamEvent> stream(@PathVariable long knowledgeBaseId,
                                       @Valid @RequestBody RagChatRequest request) {
        return ragChatService.stream(knowledgeBaseId, request.question(), request.topK());
    }
}
