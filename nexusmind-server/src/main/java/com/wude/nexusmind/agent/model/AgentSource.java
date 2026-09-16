package com.wude.nexusmind.agent.model;

public record AgentSource(
        String sourceId,
        long chunkId,
        long documentId,
        String fileName,
        Integer pageNo,
        String sectionTitle) {
}
