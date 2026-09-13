package com.wude.nexusmind.rag.retrieval;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class RetrievalServiceRegistry {

    private final Map<RetrieverType, RetrievalService> services;

    public RetrievalServiceRegistry(List<RetrievalService> services) {
        EnumMap<RetrieverType, RetrievalService> indexed = new EnumMap<>(RetrieverType.class);
        for (RetrievalService service : services) {
            RetrievalService previous = indexed.putIfAbsent(service.type(), service);
            if (previous != null) {
                throw new IllegalStateException("Duplicate retrieval service for type " + service.type());
            }
        }
        this.services = Map.copyOf(indexed);
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
}
