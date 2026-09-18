package com.wude.nexusmind.agent.model;

import java.util.List;

public record KnowledgeSearchToolResult(
        boolean found,
        String query,
        List<KnowledgeSearchItem> items,
        boolean truncated,
        int omittedItemCount,
        int estimatedTokens) {

    public KnowledgeSearchToolResult(boolean found,
                                     String query,
                                     List<KnowledgeSearchItem> items) {
        this(found, query, items, false, 0, 0);
    }

    public KnowledgeSearchToolResult {
        items = List.copyOf(items);
        if (omittedItemCount < 0 || estimatedTokens < 0) {
            throw new IllegalArgumentException("Tool-result metadata must not be negative");
        }
    }
}
