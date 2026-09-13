package com.wude.nexusmind.rag.evaluation;

public record RetrievalLatencyMetrics(double averageMs, long p50Ms, long p95Ms, long maxMs) {
}
