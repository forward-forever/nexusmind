package com.wude.nexusmind.agent.application;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public final class SpringAiAgentModelTurnStreamer implements AgentModelTurnStreamer {

    private final ChatClient chatClient;

    public SpringAiAgentModelTurnStreamer(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public Flux<ChatClientResponse> stream(Prompt prompt) {
        return chatClient.prompt(prompt)
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .stream()
                .chatClientResponse();
    }
}
