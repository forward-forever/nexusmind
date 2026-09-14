package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.config.ChunkingProperties;
import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import com.wude.nexusmind.rag.retrieval.HybridRetrievalProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalContribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalEvaluationPipelineLocalIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void runsDatasetThroughFakeRetrieverAndWritesJsonAndMarkdownWithoutChunkContent() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Path datasetFile = Files.writeString(temporaryDirectory.resolve("public-fixture.jsonl"), """
                {"id":"q001","knowledgeBaseId":12,"question":"exact question","relevantChunkIds":[101],"category":"EXACT"}
                {"id":"q002","knowledgeBaseId":12,"question":"semantic question","relevantChunkIds":[102],"category":"SEMANTIC","note":"manual label"}
                """);
        RetrievalEvaluationDataset dataset = new RetrievalDatasetLoader(objectMapper).load(datasetFile);

        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        ChunkService chunks = mock(ChunkService.class);
        DocumentService documents = mock(DocumentService.class);
        when(chunks.findByIds(anyCollection())).thenReturn(List.of(
                chunk(101L), chunk(102L)));
        when(documents.findByIds(anyCollection())).thenReturn(List.of(visibleDocument()));
        RetrievalDatasetValidator validator = new RetrievalDatasetValidator(
                knowledgeBases, chunks, documents);

        AtomicInteger retrievalCalls = new AtomicInteger();
        RetrievalService retrievalService = new RetrievalService() {
            @Override
            public RetrieverType type() {
                return RetrieverType.DENSE;
            }

            @Override
            public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
                retrievalCalls.incrementAndGet();
                List<RetrievalHit> hits = query.startsWith("exact")
                        ? List.of(hit(101L, 1.0f))
                        : rankEightHits();
                return new RetrievalResult(
                        query, knowledgeBaseId, "deterministic-embedding", 4,
                        RetrieverType.DENSE, RetrievalScoreType.COSINE, topK, hits);
            }
        };
        AtomicLong nanoTime = new AtomicLong();
        RetrievalEvaluationService service = new RetrievalEvaluationService(
                new RetrievalServiceRegistry(List.of(retrievalService)),
                validator,
                new RetrievalMetricsCalculator(),
                new ChunkingProperties(500, 100),
                new HybridRetrievalProperties(
                        new HybridRetrievalProperties.Rrf(60), 4, 20, 60),
                Clock.fixed(Instant.parse("2026-09-10T09:30:00Z"), ZoneOffset.UTC),
                () -> nanoTime.getAndAdd(10_000_000));

        RetrievalEvaluationReport report = service.evaluate(dataset, RetrieverType.DENSE);
        RetrievalReportFiles files = new RetrievalReportWriter(objectMapper)
                .write(report, temporaryDirectory.resolve("reports"));

        assertThat(retrievalCalls).hasValue(2);
        assertThat(report.metrics().at(1).hitRate()).isEqualTo(0.5);
        assertThat(report.metrics().at(10).hitRate()).isEqualTo(1.0);
        assertThat(report.failureCases()).extracting(RetrievalEvaluationCaseResult::queryId)
                .containsExactly("q002");
        assertThat(report.failureCases().get(0).firstRelevantRank()).isEqualTo(8);
        assertThat(report.latency().averageMs()).isEqualTo(10.0);
        assertThat(files.json()).exists();
        assertThat(files.markdown()).exists();
        String json = Files.readString(files.json());
        String markdown = Files.readString(files.markdown());
        assertThat(json).contains("\"retrieverType\" : \"DENSE\"", "\"datasetName\" : \"public-fixture\"")
                .doesNotContain("secret-content");
        assertThat(markdown)
                .contains("# Dense Retrieval Baseline", "| HitRate |", "### Query q002", "First relevant rank: 8")
                .doesNotContain("secret-content");
    }

    @Test
    void writesHybridIdentityParametersAndContributionsToJsonAndMarkdown() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset(
                "same-golden",
                List.of(new RetrievalEvaluationCase(
                        "q001", 12L, "hybrid question", List.of(999L), QueryCategory.SEMANTIC, null)));
        RetrievalService hybrid = new RetrievalService() {
            @Override
            public RetrieverType type() {
                return RetrieverType.HYBRID_RRF;
            }

            @Override
            public RetrievalResult retrieve(long knowledgeBaseId, String query, int topK) {
                return new RetrievalResult(
                        query, knowledgeBaseId, "deterministic-embedding", 4,
                        RetrieverType.HYBRID_RRF, RetrievalScoreType.RRF, topK,
                        List.of(new RetrievalHit(
                                101L, 10L, "fixture.txt", 0, 2.0 / 61,
                                RetrievalScoreType.RRF, "secret-content", null, null,
                                List.of(
                                        new RetrievalContribution(
                                                RetrieverType.DENSE, 1, 0.82,
                                                RetrievalScoreType.COSINE),
                                        new RetrievalContribution(
                                                RetrieverType.BM25, 1, 8.73,
                                                RetrievalScoreType.BM25)))));
            }
        };
        RetrievalEvaluationService service = new RetrievalEvaluationService(
                new RetrievalServiceRegistry(List.of(hybrid)),
                mock(RetrievalDatasetValidator.class),
                new RetrievalMetricsCalculator(),
                new ChunkingProperties(500, 100),
                new HybridRetrievalProperties(
                        new HybridRetrievalProperties.Rrf(60), 4, 20, 60),
                Clock.fixed(Instant.parse("2026-09-13T09:30:00Z"), ZoneOffset.UTC),
                new AtomicLong()::getAndIncrement);

        RetrievalEvaluationReport report = service.evaluate(dataset, RetrieverType.HYBRID_RRF);
        RetrievalReportFiles files = new RetrievalReportWriter(objectMapper)
                .write(report, temporaryDirectory.resolve("hybrid-reports"));
        String json = Files.readString(files.json());
        String markdown = Files.readString(files.markdown());

        assertThat(json)
                .contains("\"retrieverType\" : \"HYBRID_RRF\"", "\"scoreType\" : \"RRF\"")
                .contains("\"rrfK\" : 60", "\"routeCandidateMultiplier\" : 4")
                .contains("\"retrieverType\" : \"DENSE\"", "\"retrieverType\" : \"BM25\"")
                .doesNotContain("secret-content");
        assertThat(markdown)
                .contains("# Hybrid RRF Retrieval Baseline", "- RRF k: 60")
                .contains("DENSE#1 COSINE=0.82", "BM25#1 BM25=8.73")
                .doesNotContain("secret-content");
    }

    private static KnowledgeChunk chunk(long id) {
        KnowledgeChunk chunk = new KnowledgeChunk(12L, 10L, 0, "golden", null, null, 6, null);
        chunk.setId(id);
        return chunk;
    }

    private static KnowledgeDocument visibleDocument() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(10L);
        document.setKnowledgeBaseId(12L);
        document.setStatus(DocumentStatus.READY);
        document.setIndexStatus(DocumentIndexStatus.INDEXED);
        return document;
    }

    private static RetrievalHit hit(long chunkId, float score) {
        return new RetrievalHit(
                chunkId, 10L, "fixture.txt", 0, score, RetrievalScoreType.COSINE,
                "secret-content", null, null);
    }

    private static List<RetrievalHit> rankEightHits() {
        List<RetrievalHit> hits = new ArrayList<>();
        for (int rank = 1; rank <= 10; rank++) {
            hits.add(hit(rank == 8 ? 102L : 200L + rank, 1.0f - rank / 20.0f));
        }
        return hits;
    }
}
