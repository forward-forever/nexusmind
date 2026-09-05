package com.wude.nexusmind.knowledge.parser;

import com.wude.nexusmind.knowledge.exception.DocumentParsingException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}[\\t ]+(.+?)[\\t ]*#*[\\t ]*$");

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("md", "markdown");
    }

    @Override
    public ParsedDocument parse(Path documentPath) {
        String markdown = Utf8FileReader.read(documentPath, "Markdown");
        List<ParsedSection> sections = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        String currentTitle = null;
        boolean insideCodeFence = false;

        for (String line : markdown.split("\\R", -1)) {
            String leftTrimmed = line.stripLeading();
            if (leftTrimmed.startsWith("```") || leftTrimmed.startsWith("~~~")) {
                insideCodeFence = !insideCodeFence;
                currentText.append(line).append('\n');
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (!insideCodeFence && heading.matches()) {
                addSectionIfPresent(sections, currentText, currentTitle);
                currentText.setLength(0);
                currentTitle = heading.group(1).strip();
            } else {
                currentText.append(line).append('\n');
            }
        }
        addSectionIfPresent(sections, currentText, currentTitle);

        if (sections.isEmpty()) {
            throw new DocumentParsingException("Markdown document does not contain usable text");
        }
        return new ParsedDocument(sections);
    }

    private static void addSectionIfPresent(List<ParsedSection> sections,
                                            StringBuilder text,
                                            String sectionTitle) {
        String normalizedText = text.toString().strip();
        if (!normalizedText.isBlank()) {
            sections.add(new ParsedSection(normalizedText, null, sectionTitle));
        }
    }
}
