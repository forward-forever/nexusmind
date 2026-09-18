package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.model.DocumentContextToolResult;
import com.wude.nexusmind.agent.model.KnowledgeSearchToolResult;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import com.wude.nexusmind.support.TestTokenSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolResultBudgetTest {

    @Test
    void searchKeepsRankPrefixAndRegistersOnlySourcesActuallyReturned() {
        AgentProperties properties = properties(620, 2_000);
        AgentRunContext run = run(2_000);
        List<RetrievalHit> hits = List.of(
                hit(1, "a".repeat(100)), hit(2, "b".repeat(100)),
                hit(3, "c".repeat(100)), hit(4, "d".repeat(100)),
                hit(5, "e".repeat(100)));

        KnowledgeSearchToolResult result = TestTokenSupport.toolBudgeter(properties)
                .budgetSearch(run, "query", hits);

        assertThat(result.items()).extracting(item -> item.chunkId())
                .containsExactlyElementsOf(hits.stream().limit(result.items().size())
                        .map(RetrievalHit::chunkId).toList());
        assertThat(result.items()).hasSize(2);
        assertThat(result.omittedItemCount()).isEqualTo(5 - result.items().size());
        assertThat(run.sourceRegistry().snapshot()).hasSize(result.items().size());
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(620);
    }

    @Test
    void oversizedFirstSearchItemIsTruncatedButKeepsAValidSource() {
        AgentProperties properties = properties(360, 1_000);
        AgentRunContext run = run(1_000);

        KnowledgeSearchToolResult result = TestTokenSupport.toolBudgeter(properties)
                .budgetSearch(run, "query", List.of(hit(1, "x".repeat(2_000))));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo("S1");
            assertThat(item.content()).endsWith("… [truncated]");
        });
        assertThat(run.sourceRegistry().snapshot()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(360);
    }

    @Test
    void contextPreservesTargetBeforeNeighborsAndReturnsReadingOrder() {
        AgentProperties properties = properties(340, 1_000);
        AgentRunContext run = run(1_000);
        run.sourceRegistry().register(2L, 10L, "doc.pdf", 2, "section");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(10L);
        document.setOriginalFileName("doc.pdf");
        List<KnowledgeChunk> chunks = List.of(
                chunk(1, 0, "a".repeat(80)),
                chunk(2, 1, "target".repeat(80)),
                chunk(3, 2, "c".repeat(80)));

        DocumentContextToolResult result = TestTokenSupport.toolBudgeter(properties)
                .budgetDocumentContext(run, "S1", document, chunks, 2L);

        assertThat(result.items()).isNotEmpty();
        assertThat(result.items()).filteredOn(item -> item.isTarget()).singleElement()
                .satisfies(item -> {
                    assertThat(item.sourceId()).isEqualTo("S1");
                    assertThat(item.content()).endsWith("… [truncated]");
                });
        assertThat(result.items()).extracting(item -> item.sourceId()).contains("S1");
        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(340);
    }

    @Test
    void perRunBudgetRestrictsLaterCallsToTheRemainingTokens() {
        AgentRunTokenBudget budget = new AgentRunTokenBudget(1_000);
        int first = budget.allocate(800, allowed ->
                new AgentRunTokenBudget.BudgetedValue<>(allowed, 700));
        int second = budget.allocate(800, allowed ->
                new AgentRunTokenBudget.BudgetedValue<>(allowed, allowed));

        assertThat(first).isEqualTo(800);
        assertThat(second).isEqualTo(300);
        assertThat(budget.usedTokens()).isEqualTo(1_000);
        assertThat(budget.remainingTokens()).isZero();
    }

    @Test
    void concurrentReservationsCannotOverspendTheRunBudget() throws Exception {
        AgentRunTokenBudget budget = new AgentRunTokenBudget(1_000);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> allocateAfter(start, budget));
            var second = executor.submit(() -> allocateAfter(start, budget));
            start.countDown();

            assertThat(first.get() + second.get()).isEqualTo(1_000);
            assertThat(budget.usedTokens()).isEqualTo(1_000);
            assertThat(budget.remainingTokens()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static int allocateAfter(CountDownLatch start, AgentRunTokenBudget budget)
            throws InterruptedException {
        start.await();
        return budget.allocate(700, allowed ->
                new AgentRunTokenBudget.BudgetedValue<>(allowed, allowed));
    }

    private static AgentRunContext run(int runBudget) {
        return new AgentRunContext(
                "run", "session", 33L, 0, Duration.ofSeconds(30), Clock.systemUTC(),
                event -> { }, runBudget);
    }

    private static AgentProperties properties(int perCall, int perRun) {
        return new AgentProperties(
                true, 5, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                new AgentProperties.DocumentContext(1, 1),
                new AgentProperties.Memory(12, 6_000),
                new AgentProperties.SessionConcurrency(Duration.ofSeconds(45)),
                new AgentProperties.ToolResult(perCall, perRun));
    }

    private static RetrievalHit hit(long id, String content) {
        return new RetrievalHit(id, 10L, "doc.pdf", (int) id - 1, 0.9,
                RetrievalScoreType.COSINE, content, (int) id, "section");
    }

    private static KnowledgeChunk chunk(long id, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                33L, 10L, index, content, index + 1, "section", content.length(), null);
        chunk.setId(id);
        return chunk;
    }
}
