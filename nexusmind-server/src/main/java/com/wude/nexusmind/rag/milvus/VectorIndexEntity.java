package com.wude.nexusmind.rag.milvus;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;

import java.util.Objects;

public record VectorIndexEntity(KnowledgeChunk chunk, float[] embedding) {

    public VectorIndexEntity {
        Objects.requireNonNull(chunk, "Chunk is required");
        Objects.requireNonNull(embedding, "Embedding is required");
    }
}
