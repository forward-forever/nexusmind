package com.wude.nexusmind.support;


import com.wude.nexusmind.agent.application.AgentToolResultBudgeter;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.context.NexusTokenEstimator;
import com.wude.nexusmind.context.TokenBudgetCalculator;
import com.wude.nexusmind.context.TokenBudgetProperties;
import com.wude.nexusmind.context.TokenTextTruncator;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Deterministic estimator for unit tests; one UTF-16 code unit represents one estimated token. */
public final class TestTokenSupport {

    private static final NexusTokenEstimator ESTIMATOR = new NexusTokenEstimator() {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public int estimateMessages(List<Message> messages) {
            if (messages == null) {
                return 0;
            }
            StringBuilder serialized = new StringBuilder();
            for (Message message : messages) {
                serialized.append(message.getMessageType()).append(':')
                        .append(message.getText() == null ? "" : message.getText()).append('\n');
                if (message instanceof AssistantMessage assistant) {
                    assistant.getToolCalls().forEach(call -> serialized
                            .append(call.id()).append(':').append(call.name()).append(':')
                            .append(call.arguments()).append('\n'));
                }
                if (message instanceof ToolResponseMessage toolResponse) {
                    toolResponse.getResponses().forEach(response -> serialized
                            .append(response.id()).append(':').append(response.name()).append(':')
                            .append(response.responseData()).append('\n'));
                }
            }
            return estimate(serialized.toString());
        }
    };

    private TestTokenSupport() {
    }

    public static NexusTokenEstimator estimator() {
        return ESTIMATOR;
    }

    public static TokenTextTruncator truncator() {
        return new TokenTextTruncator(ESTIMATOR);
    }

    public static TokenBudgetCalculator calculator() {
        return calculator(new TokenBudgetProperties(32_768, 4_096, 4_096, 2_048));
    }

    public static TokenBudgetCalculator calculator(TokenBudgetProperties properties) {
        return new TokenBudgetCalculator(properties, ESTIMATOR);
    }

    public static AgentToolResultBudgeter toolBudgeter(AgentProperties properties) {
        return new AgentToolResultBudgeter(
                new ObjectMapper(), ESTIMATOR, truncator(), properties);
    }
}
