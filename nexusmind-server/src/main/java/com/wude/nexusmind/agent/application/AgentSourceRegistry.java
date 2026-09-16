package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.model.AgentSource;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentSourceRegistry {

    private final ConcurrentHashMap<Long, AgentSource> sourcesByChunkId = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public AgentSource register(RetrievalHit hit) {
        return sourcesByChunkId.computeIfAbsent(hit.chunkId(), ignored -> new AgentSource(
                "S" + sequence.incrementAndGet(),
                hit.chunkId(),
                hit.documentId(),
                hit.fileName(),
                hit.pageNo(),
                hit.sectionTitle()));
    }

    public List<AgentSource> snapshot() {
        return sourcesByChunkId.values().stream()
                .sorted(Comparator.comparingInt(source -> sourceNumber(source.sourceId())))
                .toList();
    }

    private static int sourceNumber(String sourceId) {
        return Integer.parseInt(sourceId.substring(1));
    }
}
