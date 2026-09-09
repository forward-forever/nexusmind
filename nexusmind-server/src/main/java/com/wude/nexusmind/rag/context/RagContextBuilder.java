package com.wude.nexusmind.rag.context;

import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.DocumentNotFoundException;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "nexusmind.rag.enabled", havingValue = "true")
public class RagContextBuilder {

    private final DocumentService documentService;
    private final RagChatProperties properties;

    public RagContextBuilder(DocumentService documentService, RagChatProperties properties) {
        this.documentService = documentService;
        this.properties = properties;
    }

    public RagContext build(List<DenseVectorHit> rankedHits) {
        if (rankedHits == null || rankedHits.isEmpty()) {
            return new RagContext("", List.of(), 0);
        }

        LinkedHashSet<Long> documentIds = rankedHits.stream()
                .map(DenseVectorHit::documentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, KnowledgeDocument> documents = documentService.findByIds(documentIds).stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));

        StringBuilder context = new StringBuilder();
        List<RagSource> includedSources = new ArrayList<>();
        for (DenseVectorHit hit : rankedHits) {
            KnowledgeDocument document = documents.get(hit.documentId());
            if (document == null) {
                throw new DocumentNotFoundException(hit.documentId());
            }
            String sourceId = "S" + (includedSources.size() + 1);
            RagSource source = new RagSource(
                    sourceId,
                    hit.chunkId(),
                    hit.documentId(),
                    document.getOriginalFileName(),
                    hit.pageNo(),
                    hit.sectionTitle(),
                    hit.score(),
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
                .append("score: ").append(String.format(Locale.ROOT, "%.6f", source.score())).append("\n\n")
                .append(source.content()).append('\n')
                .append("===== END SOURCE ").append(source.sourceId()).append(" =====\n\n");
        return block.toString();
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
