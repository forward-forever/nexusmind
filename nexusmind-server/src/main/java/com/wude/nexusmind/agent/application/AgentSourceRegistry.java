package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.model.AgentSource;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentSourceRegistry {

    private final ConcurrentHashMap<Long, AgentSource> sourcesByChunkId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> chunkIdsBySourceId = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public AgentSource register(RetrievalHit hit) {
        return register(hit.chunkId(), hit.documentId(), hit.fileName(), hit.pageNo(), hit.sectionTitle());
    }

    public synchronized AgentSource register(long chunkId,
                                             long documentId,
                                             String fileName,
                                             Integer pageNo,
                                             String sectionTitle) {
        AgentSource existing = sourcesByChunkId.get(chunkId);
        if (existing != null) {
            return existing;
        }
        AgentSource created = new AgentSource(
                "S" + sequence.incrementAndGet(), chunkId, documentId, fileName, pageNo, sectionTitle);
        sourcesByChunkId.put(chunkId, created);
        chunkIdsBySourceId.put(created.sourceId(), chunkId);
        return created;
    }

    public Optional<Long> resolveChunkId(String sourceId) {
        if (sourceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(chunkIdsBySourceId.get(sourceId));
    }

    public Optional<AgentSource> resolveSource(String sourceId) {
        return resolveChunkId(sourceId).map(sourcesByChunkId::get);
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
