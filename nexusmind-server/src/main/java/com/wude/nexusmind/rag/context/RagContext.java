package com.wude.nexusmind.rag.context;

import java.util.List;

public record RagContext(
        String text,
        List<RagSource> sources,
        int charCount,
        int estimatedTokens,
        boolean truncated) {

    public RagContext(String text, List<RagSource> sources, int charCount) {
        this(text, sources, charCount, 0, false);
    }

    public RagContext {
        text = text == null ? "" : text;
        sources = List.copyOf(sources);
        if (charCount != text.length()) {
            throw new IllegalArgumentException("Context char count must match its text length");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("Estimated context tokens must not be negative");
        }
    }
}
