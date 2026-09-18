package com.wude.nexusmind.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public final class TokenBudgetCalculator {

    private final TokenBudgetProperties properties;
    private final NexusTokenEstimator estimator;

    public TokenBudgetCalculator(TokenBudgetProperties properties,
                                 NexusTokenEstimator estimator) {
        this.properties = properties;
        this.estimator = estimator;
    }

    public int ragMessageBudget() {
        return properties.maxContextTokens()
                - properties.reservedOutputTokens()
                - properties.safetyMarginTokens();
    }

    public int agentMessageBudget() {
        return ragMessageBudget() - properties.toolDefinitionReserveTokens();
    }

    public int ragContextBudget(List<Message> fixedMessages, int configuredContextMaxTokens) {
        if (configuredContextMaxTokens <= 0) {
            throw new IllegalArgumentException("RAG context max tokens must be positive");
        }
        int remaining = ragMessageBudget() - estimator.estimateMessages(fixedMessages);
        if (remaining <= 0) {
            throw new ContextBudgetExceededException("RAG fixed prompt exceeds application budget");
        }
        return Math.min(configuredContextMaxTokens, remaining);
    }

    public int agentMemoryBudget(List<Message> fixedMessages, int configuredMemoryMaxTokens) {
        if (configuredMemoryMaxTokens <= 0) {
            throw new IllegalArgumentException("Agent memory max tokens must be positive");
        }
        int remaining = agentMessageBudget() - estimator.estimateMessages(fixedMessages);
        if (remaining < 0) {
            throw new ContextBudgetExceededException("Agent fixed prompt exceeds application budget");
        }
        return Math.min(configuredMemoryMaxTokens, remaining);
    }

    public int validateRagMessages(List<Message> messages) {
        int estimated = estimator.estimateMessages(messages);
        if (estimated > ragMessageBudget()) {
            throw new ContextBudgetExceededException("RAG prompt exceeds application budget");
        }
        return estimated;
    }

    public int validateAgentMessages(List<Message> messages) {
        int estimated = estimator.estimateMessages(messages);
        if (estimated > agentMessageBudget()) {
            throw new ContextBudgetExceededException("Agent prompt exceeds application budget");
        }
        return estimated;
    }

    public TokenBudgetProperties properties() {
        return properties;
    }
}
