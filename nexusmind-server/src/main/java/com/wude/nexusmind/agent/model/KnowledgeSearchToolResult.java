package com.wude.nexusmind.agent.model;

import java.util.List;

public record KnowledgeSearchToolResult(
        boolean found,
        String query,
        List<KnowledgeSearchItem> items) {

    public KnowledgeSearchToolResult {
        items = List.copyOf(items);
    }
}
