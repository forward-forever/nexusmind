package com.wude.nexusmind.rag.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class RetrievalReportWriter {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;

    public RetrievalReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RetrievalReportFiles write(RetrievalEvaluationReport report, Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            String baseName = report.metadata().retrieverType().name().toLowerCase(Locale.ROOT)
                    + "-" + FILE_TIMESTAMP.format(Instant.parse(report.metadata().timestamp()));
            Path json = outputDirectory.resolve(baseName + ".json");
            Path markdown = outputDirectory.resolve(baseName + ".md");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
            Files.writeString(markdown, toMarkdown(report), StandardCharsets.UTF_8);
            return new RetrievalReportFiles(json.toAbsolutePath(), markdown.toAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write retrieval evaluation report", exception);
        }
    }

    String toMarkdown(RetrievalEvaluationReport report) {
        RetrievalBaselineMetadata metadata = report.metadata();
        StringBuilder markdown = new StringBuilder()
                .append("# ").append(retrieverTitle(metadata.retrieverType().name()))
                .append(" Retrieval Baseline\n\n")
                .append("- Timestamp: ").append(metadata.timestamp()).append('\n')
                .append("- Dataset: ").append(metadata.datasetName()).append('\n')
                .append("- Queries: ").append(metadata.datasetQueryCount()).append('\n')
                .append("- Retriever: ").append(metadata.retrieverType()).append('\n')
                .append("- Query embedding: ")
                .append(metadata.embeddingModel() == null ? "Not used" : metadata.embeddingModel())
                .append('\n')
                .append("- Query embedding dimension: ")
                .append(metadata.embeddingModel() == null ? "Not applicable" : metadata.embeddingDimension())
                .append('\n')
                .append("- Chunking: ").append(metadata.chunkSizeChars()).append(" chars, overlap ")
                .append(metadata.chunkOverlapChars()).append(" chars\n")
                .append("- Score type: ").append(metadata.scoreType()).append('\n');
        appendHybridMetadata(markdown, metadata.hybrid());
        appendRerankMetadata(markdown, metadata.rerank());
        markdown.append('\n')
                .append("## Ranking Metrics\n\n")
                .append("| Metric | @1 | @3 | @5 | @10 |\n")
                .append("|---|---:|---:|---:|---:|\n")
                .append(metricRow("HitRate", report, MetricValue.HIT_RATE))
                .append(metricRow("Macro Recall", report, MetricValue.RECALL))
                .append(metricRow("MRR", report, MetricValue.MRR))
                .append("\n## Retrieval Latency\n\n")
                .append("| Average | P50 | P95 | Max |\n")
                .append("|---:|---:|---:|---:|\n")
                .append(String.format(Locale.ROOT, "| %.2f ms | %d ms | %d ms | %d ms |%n%n",
                        report.latency().averageMs(), report.latency().p50Ms(),
                        report.latency().p95Ms(), report.latency().maxMs()))
                .append("> Latency is a local development baseline, not a production benchmark.\n\n")
                .append("## Category Breakdown\n\n")
                .append("| Category | Queries | HitRate@5 | MRR@5 |\n")
                .append("|---|---:|---:|---:|\n");
        for (RetrievalCategoryBreakdown category : report.categoryBreakdown()) {
            markdown.append(String.format(Locale.ROOT, "| %s | %d | %.4f | %.4f |%n",
                    category.category(), category.queryCount(), category.hitRateAt5(), category.mrrAt5()));
        }
        markdown.append("\n## Failure Cases\n\n");
        if (report.failureCases().isEmpty()) {
            markdown.append("No Top-5 misses or low-ranked relevant hits.\n");
        } else {
            for (RetrievalEvaluationCaseResult failure : report.failureCases()) {
                markdown.append("### Query ").append(failure.queryId()).append("\n\n")
                        .append("- Category: ").append(failure.category()).append('\n')
                        .append("- Question: ").append(singleLine(failure.question())).append('\n')
                        .append("- Expected: ").append(failure.relevantChunkIds()).append('\n')
                        .append("- Retrieved: ")
                        .append(failure.retrieved().stream()
                                .map(RetrievalReportWriter::retrievedSummary)
                                .toList())
                        .append('\n')
                        .append("- First relevant rank: ")
                        .append(failure.firstRelevantRank() == null ? "NONE" : failure.firstRelevantRank())
                        .append("\n\n");
            }
        }
        return markdown.toString();
    }

    private static void appendHybridMetadata(StringBuilder markdown,
                                             HybridEvaluationMetadata hybrid) {
        if (hybrid == null) {
            return;
        }
        markdown.append("- RRF k: ").append(hybrid.rrfK()).append('\n')
                .append("- Routes: ").append(hybrid.routes()).append('\n')
                .append("- Route candidates: multiplier=")
                .append(hybrid.routeCandidateMultiplier())
                .append(", min=").append(hybrid.minRouteCandidates())
                .append(", max=").append(hybrid.maxRouteCandidates())
                .append('\n');
    }

    private static void appendRerankMetadata(StringBuilder markdown,
                                             RerankEvaluationMetadata rerank) {
        if (rerank == null) {
            return;
        }
        markdown.append("- Rerank model: ").append(rerank.model()).append('\n')
                .append("- Rerank candidates: baseline=").append(rerank.candidateTopN())
                .append(", max=").append(rerank.maxCandidateTopN()).append('\n')
                .append("- Rerank upstream: ").append(rerank.upstreamRetriever()).append('\n');
    }

    private static String retrievedSummary(RetrievalEvaluationCaseResult.RetrievedChunk hit) {
        if (hit.contributions().isEmpty()) {
            return hit.chunkId() + "@" + hit.rank() + " " + hit.scoreType() + "=" + hit.score();
        }
        String contributions = hit.contributions().stream()
                .map(contribution -> "%s#%d %s=%s".formatted(
                        contribution.retrieverType(),
                        contribution.rank(),
                        contribution.rawScoreType(),
                        contribution.rawScore()))
                .toList()
                .toString();
        String upstream = hit.rerank() == null
                ? ""
                : " preRerankRank=" + hit.rerank().preRerankRank()
                + " preRerankScore=" + hit.rerank().preRerankScore()
                + " preRerankScoreType=" + hit.rerank().preRerankScoreType();
        return hit.chunkId() + "@" + hit.rank() + " " + hit.scoreType() + "=" + hit.score()
                + upstream + " contributions=" + contributions;
    }

    private static String metricRow(String name,
                                    RetrievalEvaluationReport report,
                                    MetricValue value) {
        StringBuilder row = new StringBuilder("| ").append(name).append(" |");
        for (int k : RetrievalMetricsCalculator.BASELINE_K_VALUES) {
            RetrievalMetrics.AtK metric = report.metrics().at(k);
            double number = switch (value) {
                case HIT_RATE -> metric.hitRate();
                case RECALL -> metric.recall();
                case MRR -> metric.mrr();
            };
            row.append(String.format(Locale.ROOT, " %.4f |", number));
        }
        return row.append('\n').toString();
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String retrieverTitle(String value) {
        if ("BM25".equals(value)) {
            return value;
        }
        if ("HYBRID_RRF".equals(value)) {
            return "Hybrid RRF";
        }
        if ("HYBRID_RERANK".equals(value)) {
            return "Hybrid Rerank";
        }
        String lowerCase = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lowerCase.charAt(0)) + lowerCase.substring(1);
    }

    private enum MetricValue {
        HIT_RATE,
        RECALL,
        MRR
    }
}
