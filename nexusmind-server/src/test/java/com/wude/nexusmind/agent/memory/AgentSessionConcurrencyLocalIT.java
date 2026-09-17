package com.wude.nexusmind.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "nexusmind.agent.enabled=false",
                "nexusmind.document-task.worker-enabled=false",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "nexusmind.vector.enabled=false",
                "nexusmind.milvus.enabled=false",
                "nexusmind.rag.enabled=false"
        })
@ActiveProfiles("local")
class AgentSessionConcurrencyLocalIT {

    @Autowired
    private AgentSessionConcurrencyService concurrency;

    @Autowired
    private AgentConversationMemoryService memory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void serializesSameSessionAllowsExpiryAndFencesOldOwner() throws Exception {
        String session = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_session(session_id, knowledge_base_id) VALUES (?, ?)",
                session, 33L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            String runA = UUID.randomUUID().toString();
            String runB = UUID.randomUUID().toString();
            Future<Boolean> first = executor.submit(() -> acquire(start, session, runA));
            Future<Boolean> second = executor.submit(() -> acquire(start, session, runB));
            start.countDown();

            List<Boolean> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            String owner = jdbc.queryForObject(
                    "SELECT active_run_id FROM agent_session WHERE session_id = ?",
                    String.class, session);
            String oldOwner = owner;
            String newOwner = UUID.randomUUID().toString();

            jdbc.update("UPDATE agent_session SET run_lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE session_id = ?",
                    session);
            assertThat(concurrency.acquire(33L, session, newOwner, Duration.ofSeconds(45)))
                    .isEqualTo(session);
            assertThat(concurrency.release(session, oldOwner)).isFalse();
            assertThatThrownBy(() -> memory.appendSuccessfulTurn(
                    session, oldOwner, "old question", "old answer"))
                    .isInstanceOf(AgentSessionLeaseLostException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_message WHERE session_id = ?",
                    Integer.class, session)).isZero();
            assertThat(concurrency.release(session, newOwner)).isTrue();
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM agent_message WHERE session_id = ?", session);
            jdbc.update("DELETE FROM agent_session WHERE session_id = ?", session);
        }
    }

    @Test
    void allowsDifferentSessionsToBeAcquiredConcurrently() throws Exception {
        String sessionA = UUID.randomUUID().toString();
        String sessionB = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_session(session_id, knowledge_base_id) VALUES (?, ?)",
                sessionA, 33L);
        jdbc.update("INSERT INTO agent_session(session_id, knowledge_base_id) VALUES (?, ?)",
                sessionB, 33L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = executor.submit(() -> acquire(
                    start, sessionA, UUID.randomUUID().toString()));
            Future<Boolean> second = executor.submit(() -> acquire(
                    start, sessionB, UUID.randomUUID().toString()));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsOnly(true);
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM agent_session WHERE session_id IN (?, ?)", sessionA, sessionB);
        }
    }

    private boolean acquire(CountDownLatch start, String session, String runId) throws Exception {
        start.await();
        try {
            concurrency.acquire(33L, session, runId, Duration.ofSeconds(45));
            return true;
        } catch (AgentSessionBusyException busy) {
            return false;
        }
    }
}
