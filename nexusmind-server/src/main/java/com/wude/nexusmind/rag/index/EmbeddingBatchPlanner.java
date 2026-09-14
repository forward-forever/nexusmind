package com.wude.nexusmind.rag.index;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.model.config.EmbeddingProperties;

import java.util.ArrayList;
import java.util.List;

public class EmbeddingBatchPlanner {

    private final EmbeddingProperties properties;

    public EmbeddingBatchPlanner(EmbeddingProperties properties) {
        this.properties = properties;
    }

    public List<List<KnowledgeChunk>> plan(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<List<KnowledgeChunk>> batches = new ArrayList<>();
        List<KnowledgeChunk> current = new ArrayList<>(properties.batchSize());
        int currentChars = 0;
        for (KnowledgeChunk chunk : chunks) {
            if (chunk == null || chunk.getContent() == null) {
                throw new IllegalArgumentException("Embedding chunk content is required");
            }
            int itemChars = chunk.getContent().length();
            if (!current.isEmpty()
                    && (current.size() >= properties.batchSize()
                    || currentChars + itemChars > properties.maxBatchChars())) {
                // 当前批次已满，添加到批次列表并开始新的批次
                batches.add(List.copyOf(current));
                current.clear();
                currentChars = 0;
            }
            current.add(chunk);
            currentChars += itemChars;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return List.copyOf(batches);
    }
}
