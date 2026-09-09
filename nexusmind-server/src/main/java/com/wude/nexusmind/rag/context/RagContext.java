package com.wude.nexusmind.rag.context;

import java.util.List;

public record RagContext(String text, List<RagSource> sources, int charCount) {

    public RagContext {
        text = text == null ? "" : text;
        sources = List.copyOf(sources);
        if (charCount != text.length()) {
            throw new IllegalArgumentException("Context char count must match its text length");
        }
    }
}
