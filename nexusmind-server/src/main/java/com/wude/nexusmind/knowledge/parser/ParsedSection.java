package com.wude.nexusmind.knowledge.parser;

import java.util.Objects;

public record ParsedSection(String text, Integer pageNo, String sectionTitle) {

    public ParsedSection {
        Objects.requireNonNull(text, "Parsed section text is required");
    }
}
