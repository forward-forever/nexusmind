package com.wude.nexusmind.agent.model;

public record KnowledgeSearchItem(
        String sourceId,
        long chunkId,
        long documentId,
        String fileName,
        Integer pageNo,
        String sectionTitle,
        String content) {
}
