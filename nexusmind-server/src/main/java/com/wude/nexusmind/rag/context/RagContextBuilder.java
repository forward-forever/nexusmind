package com.wude.nexusmind.rag.context;

import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "nexusmind.rag.enabled", havingValue = "true")
public class RagContextBuilder {

    private final RagChatProperties properties;

    public RagContextBuilder(RagChatProperties properties) {
        this.properties = properties;
    }

    public RagContext build(List<RetrievalHit> rankedHits) {
        if (rankedHits == null || rankedHits.isEmpty()) {
            return new RagContext("", List.of(), 0);
        }

        StringBuilder context = new StringBuilder();
        List<RagSource> includedSources = new ArrayList<>();
        for (RetrievalHit hit : rankedHits) {
            String sourceId = "S" + (includedSources.size() + 1);
            RagSource source = new RagSource(
                    sourceId,
                    hit.chunkId(),
                    hit.documentId(),
                    hit.fileName(),
                    hit.pageNo(),
                    hit.sectionTitle(),
                    hit.score(),
                    hit.scoreType(),
                    hit.content());
            String block = format(source);
            if (context.length() + block.length() > properties.maxContextChars()) {
                break;
            }
            context.append(block);
            includedSources.add(source);
        }
        return new RagContext(context.toString(), includedSources, context.length());
    }

    private static String format(RagSource source) {
        StringBuilder block = new StringBuilder()
                .append("===== SOURCE ").append(source.sourceId()).append(" =====\n")
                .append("file: ").append(singleLine(source.fileName())).append('\n');
        if (source.pageNo() != null) {
            block.append("page: ").append(source.pageNo()).append('\n');
        }
        if (source.sectionTitle() != null) {
            block.append("section: ").append(singleLine(source.sectionTitle())).append('\n');
        }
        block.append("chunk_id: ").append(source.chunkId()).append('\n')
                .append("score: ").append(source.scoreType()).append(' ')
                .append(String.format(Locale.ROOT, "%.6f", source.score())).append("\n\n")
                .append(source.content()).append('\n')
                .append("===== END SOURCE ").append(source.sourceId()).append(" =====\n\n");
        return block.toString();
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
