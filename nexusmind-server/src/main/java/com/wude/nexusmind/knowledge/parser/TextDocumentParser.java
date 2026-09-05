package com.wude.nexusmind.knowledge.parser;

import com.wude.nexusmind.knowledge.exception.DocumentParsingException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("txt");
    }

    @Override
    public ParsedDocument parse(Path documentPath) {
        String text = Utf8FileReader.read(documentPath, "TXT").strip();
        if (text.isBlank()) {
            throw new DocumentParsingException("TXT document does not contain usable text");
        }
        return new ParsedDocument(List.of(new ParsedSection(text, null, null)));
    }
}
