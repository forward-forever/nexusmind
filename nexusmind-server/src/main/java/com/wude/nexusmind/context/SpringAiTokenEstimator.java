package com.wude.nexusmind.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.List;

public final class SpringAiTokenEstimator implements NexusTokenEstimator {

    private final TokenCountEstimator delegate;

    public SpringAiTokenEstimator(TokenCountEstimator delegate) {
        this.delegate = delegate;
    }

    @Override
    public int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : delegate.estimate(text);
    }

    @Override
    public int estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        StringBuilder serialized = new StringBuilder();
        for (Message message : messages) {
            serialized.append('<').append(message.getMessageType()).append(">\n")
                    .append(message.getText() == null ? "" : message.getText()).append('\n');
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    serialized.append("tool_call:")
                            .append(call.id()).append(':')
                            .append(call.name()).append(':')
                            .append(call.arguments()).append('\n');
                }
            }
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    serialized.append("tool_response:")
                            .append(response.id()).append(':')
                            .append(response.name()).append(':')
                            .append(response.responseData()).append('\n');
                }
            }
        }
        return estimate(serialized.toString());
    }
}
