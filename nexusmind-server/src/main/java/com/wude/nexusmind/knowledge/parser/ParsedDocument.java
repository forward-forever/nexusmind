package com.wude.nexusmind.knowledge.parser;

import java.util.List;

public record ParsedDocument(List<ParsedSection> sections) {

    public ParsedDocument {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
