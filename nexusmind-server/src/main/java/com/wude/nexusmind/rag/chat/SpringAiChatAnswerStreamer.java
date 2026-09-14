package com.wude.nexusmind.rag.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class SpringAiChatAnswerStreamer implements ChatAnswerStreamer {

    private final ChatClient chatClient;

    public SpringAiChatAnswerStreamer(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * Stream the answer to the prompt
     *
     * @param prompt the prompt
     * @return the stream of the answer
     */
    @Override
    public Flux<String> stream(RagPrompt prompt) {
        return chatClient.prompt()
                .system(prompt.systemMessage())
                .user(prompt.userMessage())
                .stream()
                .content();
    }
}
