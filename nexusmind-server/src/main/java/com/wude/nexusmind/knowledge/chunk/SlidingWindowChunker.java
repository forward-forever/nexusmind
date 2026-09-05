package com.wude.nexusmind.knowledge.chunk;

import com.wude.nexusmind.knowledge.config.ChunkingProperties;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.parser.ParsedDocument;
import com.wude.nexusmind.knowledge.parser.ParsedSection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SlidingWindowChunker implements DocumentChunker {

    private static final double MINIMUM_BOUNDARY_RATIO = 0.75;
    private static final String SENTENCE_BOUNDARIES = "。！？；.!?;";

    private final int chunkSizeChars;
    private final int chunkOverlapChars;

    public SlidingWindowChunker(ChunkingProperties properties) {
        this.chunkSizeChars = properties.chunkSizeChars();
        this.chunkOverlapChars = properties.chunkOverlapChars();
    }

    @Override
    public List<KnowledgeChunk> chunk(ParsedDocument parsedDocument,
                                      long knowledgeBaseId,
                                      long documentId) {
        if (parsedDocument == null) {
            throw new IllegalArgumentException("Parsed document is required");
        }
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (ParsedSection section : parsedDocument.sections()) {
            String text = section.text();
            if (text == null || text.isBlank()) {
                continue;
            }

            int start = 0;
            while (start < text.length()) {
                int hardEnd = Math.min(start + chunkSizeChars, text.length());
                int end = hardEnd == text.length() ? hardEnd : findNaturalBoundary(text, start, hardEnd);
                if (end <= start) {
                    end = hardEnd;
                }

                String content = text.substring(start, end).strip();
                if (!content.isBlank()) {
                    chunks.add(new KnowledgeChunk(
                            knowledgeBaseId,
                            documentId,
                            chunkIndex++,
                            content,
                            section.pageNo(),
                            section.sectionTitle(),
                            content.length(),
                            null
                    ));
                }

                if (end >= text.length()) {
                    break;
                }
                int nextStart = end - chunkOverlapChars;
                start = Math.max(start + 1, nextStart);
            }
        }
        return List.copyOf(chunks);
    }

    private int findNaturalBoundary(String text, int start, int hardEnd) {
        int minimumEnd = start + Math.max(1,
                (int) Math.floor((hardEnd - start) * MINIMUM_BOUNDARY_RATIO));

        int paragraph = text.lastIndexOf("\n\n", hardEnd - 2);
        if (paragraph >= minimumEnd) {
            return paragraph + 2;
        }

        int newline = text.lastIndexOf('\n', hardEnd - 1);
        if (newline >= minimumEnd) {
            return newline + 1;
        }

        for (int index = hardEnd - 1; index >= minimumEnd; index--) {
            if (SENTENCE_BOUNDARIES.indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }

        for (int index = hardEnd - 1; index >= minimumEnd; index--) {
            char character = text.charAt(index);
            if (character == ' ' || character == '\t') {
                return index + 1;
            }
        }
        return hardEnd;
    }
}
