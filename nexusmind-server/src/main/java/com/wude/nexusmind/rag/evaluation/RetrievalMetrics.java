package com.wude.nexusmind.rag.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RetrievalMetrics(Map<Integer, AtK> values) {

    public RetrievalMetrics {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public AtK at(int k) {
        AtK metric = values.get(k);
        if (metric == null) {
            throw new IllegalArgumentException("Metrics do not contain K=" + k);
        }
        return metric;
    }

    public record AtK(int k, double hitRate, double recall, double mrr) {
    }
}
