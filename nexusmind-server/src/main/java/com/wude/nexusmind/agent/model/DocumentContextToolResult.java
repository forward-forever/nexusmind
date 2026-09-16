package com.wude.nexusmind.agent.model;

import java.util.List;

public record DocumentContextToolResult(
        boolean found,
        String requestedSourceId,
        String reason,
        List<DocumentContextItem> items) {

    public DocumentContextToolResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static DocumentContextToolResult unknownSource(String sourceId) {
        return new DocumentContextToolResult(false, sourceId, "UNKNOWN_SOURCE", List.of());
    }

    public static DocumentContextToolResult sourceUnavailable(String sourceId) {
        return new DocumentContextToolResult(false, sourceId, "SOURCE_UNAVAILABLE", List.of());
    }

    public static DocumentContextToolResult found(String sourceId, List<DocumentContextItem> items) {
        return new DocumentContextToolResult(true, sourceId, null, items);
    }
}
