package com.wude.nexusmind.agent.application;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public interface AgentModelTurnStreamer {

    Flux<ChatClientResponse> stream(Prompt prompt);
}
