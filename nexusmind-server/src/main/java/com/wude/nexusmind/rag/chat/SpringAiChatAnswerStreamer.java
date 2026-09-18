package com.wude.nexusmind.rag.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import com.wude.nexusmind.context.TokenBudgetProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class SpringAiChatAnswerStreamer implements ChatAnswerStreamer {

    private final ChatClient chatClient;

    private final TokenBudgetProperties tokenBudgetProperties;

    public SpringAiChatAnswerStreamer(ChatClient.Builder builder,
                                      TokenBudgetProperties tokenBudgetProperties) {
        this.chatClient = builder.build();
        this.tokenBudgetProperties = tokenBudgetProperties;
    }

    /**
     * Stream the answer to the prompt
     *
     * @param prompt the prompt
     * @return the stream of the answer
     */
    @Override
    public Flux<String> stream(RagPrompt prompt) {
        ChatOptions.Builder options = ChatOptions.builder();
        if (tokenBudgetProperties.reservedOutputTokens() > 0) {
            options.maxTokens(tokenBudgetProperties.reservedOutputTokens());
        }
        return chatClient.prompt()
                .system(prompt.systemMessage())
                .user(prompt.userMessage())
                .options(options)
                .stream()
                .content();
    }
}
