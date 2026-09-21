package com.wude.nexusmind.rag.retrieval;

import com.wude.nexusmind.observability.NexusMindMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务注册类
 */
public class RetrievalServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceRegistry.class);
    private final Map<RetrieverType, RetrievalService> services;

    public RetrievalServiceRegistry(List<RetrievalService> services) {
        this.services = index(services, null);
    }

    public RetrievalServiceRegistry(List<RetrievalService> services, NexusMindMetrics metrics) {
        this.services = index(services, metrics);
    }

    private static Map<RetrieverType, RetrievalService> index(
            List<RetrievalService> services, NexusMindMetrics metrics) {
        EnumMap<RetrieverType, RetrievalService> indexed = new EnumMap<>(RetrieverType.class);
        for (RetrievalService service : services) {
            RetrievalService instrumented = metrics == null
                    ? service
                    : new InstrumentedRetrievalService(service, metrics);
            RetrievalService previous = indexed.putIfAbsent(service.type(), instrumented);
            if (previous != null) {
                throw new IllegalStateException("Duplicate retrieval service for type " + service.type());
            }
        }
        return Map.copyOf(indexed);
    }

    public RetrievalService get(RetrieverType type) {
        if (type == null) {
            throw new IllegalArgumentException("Retriever type is required");
        }
        RetrievalService service = services.get(type);
        if (service == null) {
            throw new IllegalArgumentException("Unsupported retriever type: " + type);
        }
        return service;
    }

    private record InstrumentedRetrievalService(RetrievalService delegate, NexusMindMetrics metrics) implements RetrievalService {

        @Override
        public RetrieverType type() {
            return delegate.type();
        }

        @Override
        public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
            Timer.Sample sample = metrics.start();
            long startedAt = System.nanoTime();
            try {
                RetrievalResult result = delegate.retrieve(knowledgeBaseId, query, topK);
                metrics.retrievalCompleted(sample, type().name(), "success");
                log.info("Retrieval completed: retriever={}, topK={}, resultCount={}, durationMs={}, outcome=success",
                        type(), topK, result.hits().size(), elapsedMillis(startedAt));
                return result;
            } catch (RuntimeException failure) {
                metrics.retrievalCompleted(sample, type().name(), "error");
                log.warn("Retrieval failed: retriever={}, topK={}, durationMs={}, outcome=error, errorType={}",
                        type(), topK, elapsedMillis(startedAt), failure.getClass().getSimpleName());
                throw failure;
            }
        }

        private static long elapsedMillis(long startedAt) {
            return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        }
    }
}
