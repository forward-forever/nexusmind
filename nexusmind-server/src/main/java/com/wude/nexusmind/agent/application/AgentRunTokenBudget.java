package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.context.ContextBudgetExceededException;

import java.util.function.IntFunction;

/** Run-scoped, thread-safe accounting for serialized tool results. */
public final class AgentRunTokenBudget {

    private final int maxTokens;
    private int usedTokens;

    public AgentRunTokenBudget(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Tool-result run token budget must be positive");
        }
        this.maxTokens = maxTokens;
    }

    public synchronized <T> T allocate(int maxTokensPerCall,
                                       IntFunction<BudgetedValue<T>> planner) {
        if (maxTokensPerCall <= 0) {
            throw new IllegalArgumentException("Tool-result call token budget must be positive");
        }
        int allowed = Math.min(maxTokensPerCall, maxTokens - usedTokens);
        if (allowed <= 0) {
            throw new ContextBudgetExceededException("Agent tool-result run budget is exhausted");
        }
        BudgetedValue<T> planned = planner.apply(allowed);
        if (planned.estimatedTokens() < 0 || planned.estimatedTokens() > allowed) {
            throw new ContextBudgetExceededException("Agent tool result exceeds its reserved budget");
        }
        usedTokens += planned.estimatedTokens();
        return planned.value();
    }

    public synchronized int usedTokens() {
        return usedTokens;
    }

    public synchronized int remainingTokens() {
        return maxTokens - usedTokens;
    }

    public record BudgetedValue<T>(T value, int estimatedTokens) {
    }
}
