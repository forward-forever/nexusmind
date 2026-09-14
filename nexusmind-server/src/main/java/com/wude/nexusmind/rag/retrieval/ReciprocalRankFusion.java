package com.wude.nexusmind.rag.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ReciprocalRankFusion {

    private static final Logger log = LoggerFactory.getLogger(ReciprocalRankFusion.class);

    public List<RetrievalHit> fuse(List<RetrievalHit> denseHits,
                                   List<RetrievalHit> bm25Hits,
                                   int k,
                                   int finalTopK) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k must be positive");
        }
        if (finalTopK <= 0) {
            throw new IllegalArgumentException("Final topK must be positive");
        }
        Map<Long, Accumulator> accumulators = new LinkedHashMap<>();
        addRoute(accumulators, safeHits(denseHits), RetrieverType.DENSE, RetrievalScoreType.COSINE, k);
        addRoute(accumulators, safeHits(bm25Hits), RetrieverType.BM25, RetrievalScoreType.BM25, k);
        return accumulators.values().stream()
                .sorted(Comparator.comparingDouble(Accumulator::score).reversed()
                        .thenComparing(Comparator.comparingInt(Accumulator::contributionCount).reversed())
                        .thenComparingInt(Accumulator::bestRank)
                        .thenComparingLong(Accumulator::chunkId))
                .limit(finalTopK)
                .map(Accumulator::toHit)
                .toList();
    }

    private static List<RetrievalHit> safeHits(List<RetrievalHit> hits) {
        return hits == null ? List.of() : hits;
    }

    private static void addRoute(Map<Long, Accumulator> accumulators,
                                 List<RetrievalHit> hits,
                                 RetrieverType retrieverType,
                                 RetrievalScoreType expectedScoreType,
                                 int k) {
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHit hit = hits.get(index);
            int rank = index + 1;
            if (hit.scoreType() != expectedScoreType) {
                throw new IllegalArgumentException(
                        "%s route returned score type %s instead of %s"
                                .formatted(retrieverType, hit.scoreType(), expectedScoreType));
            }
            Accumulator accumulator = accumulators.computeIfAbsent(
                    hit.chunkId(), ignored -> new Accumulator(hit));
            accumulator.add(hit, new RetrievalContribution(
                    retrieverType, rank, hit.score(), hit.scoreType()), k);
        }
    }

    private static final class Accumulator {
        private final RetrievalHit canonical;
        private final List<RetrievalContribution> contributions = new ArrayList<>(2);
        private double score;
        private int bestRank = Integer.MAX_VALUE;

        private Accumulator(RetrievalHit canonical) {
            this.canonical = canonical;
        }

        private void add(RetrievalHit hit, RetrievalContribution contribution, int k) {
            if (contributions.stream().anyMatch(existing ->
                    existing.retrieverType() == contribution.retrieverType())) {
                log.warn("Ignoring duplicate chunk in retrieval route: chunkId={}, retrieverType={}",
                        hit.chunkId(), contribution.retrieverType());
                return;
            }
            if (canonical.documentId() != hit.documentId()
                    || !Objects.equals(canonical.content(), hit.content())) {
                log.warn("Conflicting retrieval metadata for chunkId={}: canonicalDocumentId={}, "
                                + "routeDocumentId={}, retrieverType={}",
                        hit.chunkId(), canonical.documentId(), hit.documentId(), contribution.retrieverType());
            }
            score += 1.0d / (k + contribution.rank());
            bestRank = Math.min(bestRank, contribution.rank());
            contributions.add(contribution);
        }

        private double score() {
            return score;
        }

        private int contributionCount() {
            return contributions.size();
        }

        private int bestRank() {
            return bestRank;
        }

        private long chunkId() {
            return canonical.chunkId();
        }

        private RetrievalHit toHit() {
            return new RetrievalHit(
                    canonical.chunkId(),
                    canonical.documentId(),
                    canonical.fileName(),
                    canonical.chunkIndex(),
                    score,
                    RetrievalScoreType.RRF,
                    canonical.content(),
                    canonical.pageNo(),
                    canonical.sectionTitle(),
                    contributions);
        }
    }
}
