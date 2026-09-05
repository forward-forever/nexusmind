package com.wude.nexusmind.knowledge.parser;

import com.wude.nexusmind.knowledge.exception.UnsupportedDocumentTypeException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DocumentParserRegistry {

    private final Map<String, DocumentParser> parsersByExtension;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        Map<String, DocumentParser> registry = new HashMap<>();
        for (DocumentParser parser : parsers) {
            for (String extension : parser.supportedExtensions()) {
                String normalized = extension.toLowerCase(Locale.ROOT);
                DocumentParser previous = registry.putIfAbsent(normalized, parser);
                if (previous != null) {
                    throw new IllegalStateException("Multiple document parsers registered for extension: " + normalized);
                }
            }
        }
        this.parsersByExtension = Map.copyOf(registry);
    }

    public DocumentParser parserFor(String fileName) {
        String extension = extensionOf(fileName);
        DocumentParser parser = parsersByExtension.get(extension);
        if (parser == null) {
            throw new UnsupportedDocumentTypeException(fileName);
        }
        return parser;
    }

    public void requireSupported(String fileName) {
        parserFor(fileName);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new UnsupportedDocumentTypeException(String.valueOf(fileName));
        }
        String normalizedSeparators = fileName.replace('\\', '/');
        String baseName = normalizedSeparators.substring(normalizedSeparators.lastIndexOf('/') + 1);
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == baseName.length() - 1) {
            throw new UnsupportedDocumentTypeException(fileName);
        }
        return baseName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
