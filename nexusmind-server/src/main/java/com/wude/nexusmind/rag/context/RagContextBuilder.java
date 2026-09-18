package com.wude.nexusmind.rag.context;

import com.wude.nexusmind.context.ContextBudgetExceededException;
import com.wude.nexusmind.context.NexusTokenEstimator;
import com.wude.nexusmind.context.TokenTextTruncator;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "nexusmind.rag.enabled", havingValue = "true")
public class RagContextBuilder {

    private final RagContextProperties properties;
    private final NexusTokenEstimator estimator;
    private final TokenTextTruncator truncator;

    public RagContextBuilder(RagContextProperties properties,
                             NexusTokenEstimator estimator,
                             TokenTextTruncator truncator) {
        this.properties = properties;
        this.estimator = estimator;
        this.truncator = truncator;
    }

    public RagContext build(List<RetrievalHit> rankedHits) {
        return build(rankedHits, properties.maxTokens());
    }

    public RagContext build(List<RetrievalHit> rankedHits, int contextBudgetTokens) {
        if (contextBudgetTokens <= 0) {
            throw new IllegalArgumentException("RAG context budget must be positive");
        }
        if (rankedHits == null || rankedHits.isEmpty()) {
            return new RagContext("", List.of(), 0, 0, false);
        }

        StringBuilder context = new StringBuilder();
        List<RagSource> includedSources = new ArrayList<>();
        boolean truncated = false;
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
            String prospective = context + block;
            if (estimator.estimate(prospective) > contextBudgetTokens) {
                truncated = true;
                if (includedSources.isEmpty()) {
                    TokenTextTruncator.TruncatedText shortened = truncator
                            .truncateToFitRendered(
                                    source.content(), contextBudgetTokens,
                                    content -> format(withContent(source, content)))
                            .orElseThrow(() -> new ContextBudgetExceededException(
                                    "RAG context budget cannot fit source metadata"));
                    RagSource truncatedSource = withContent(source, shortened.text());
                    String truncatedBlock = format(truncatedSource);
                    context.append(truncatedBlock);
                    includedSources.add(truncatedSource);
                }
                break;
            }
            context.append(block);
            includedSources.add(source);
        }
        String text = context.toString();
        return new RagContext(
                text, includedSources, text.length(), estimator.estimate(text), truncated);
    }

    static String format(RagSource source) {
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

    private static RagSource withContent(RagSource source, String content) {
        return new RagSource(
                source.sourceId(), source.chunkId(), source.documentId(), source.fileName(),
                source.pageNo(), source.sectionTitle(), source.score(), source.scoreType(), content);
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
