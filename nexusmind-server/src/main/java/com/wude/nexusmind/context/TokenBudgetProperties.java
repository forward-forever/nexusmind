package com.wude.nexusmind.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusmind.ai.context")
public record TokenBudgetProperties(
        int maxContextTokens,
        int reservedOutputTokens,
        int safetyMarginTokens,
        int toolDefinitionReserveTokens) {

    public TokenBudgetProperties {
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException("Maximum context tokens must be positive");
        }
        if (reservedOutputTokens < 0 || safetyMarginTokens < 0
                || toolDefinitionReserveTokens < 0) {
            throw new IllegalArgumentException("Context token reserves must not be negative");
        }
        long reserved = (long) reservedOutputTokens + safetyMarginTokens
                + toolDefinitionReserveTokens;
        if (reserved >= maxContextTokens) {
            throw new IllegalArgumentException(
                    "Output, safety, and tool-definition reserves must leave prompt capacity");
        }
    }
}
