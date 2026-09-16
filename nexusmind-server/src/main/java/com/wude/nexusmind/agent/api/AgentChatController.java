package com.wude.nexusmind.agent.api;

import com.wude.nexusmind.agent.application.AgentChatService;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/knowledge-bases")
@ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
public class AgentChatController {

    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    @PostMapping(
            value = "/{knowledgeBaseId}/agent/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> chat(
            @PathVariable long knowledgeBaseId,
            @Valid @RequestBody AgentChatRequest request) {
        return agentChatService.chat(knowledgeBaseId, request.sessionId(), request.message())
                .map(event -> ServerSentEvent.builder(event)
                        .event(event.type())
                        .build());
    }
}
