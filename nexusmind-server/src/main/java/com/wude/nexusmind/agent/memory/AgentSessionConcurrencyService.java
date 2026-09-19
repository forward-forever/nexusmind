package com.wude.nexusmind.agent.memory;

import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentSessionEntity;
import com.wude.nexusmind.agent.memory.domain.AgentSessionType;
import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.wude.nexusmind.observability.NexusMindMetrics;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class AgentSessionConcurrencyService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionConcurrencyService.class);

    private final AgentSessionMapper sessionMapper;
    private final NexusMindMetrics metrics;

    public AgentSessionConcurrencyService(AgentSessionMapper sessionMapper) {
        this(sessionMapper, NexusMindMetrics.noop());
    }

    @Autowired
    public AgentSessionConcurrencyService(AgentSessionMapper sessionMapper,
                                          NexusMindMetrics metrics) {
        this.sessionMapper = sessionMapper;
        this.metrics = metrics;
    }

    @Transactional
    public String acquire(long knowledgeBaseId,
                          String requestedSessionId,
                          String runId,
                          Duration leaseDuration) {
        return acquire(knowledgeBaseId, requestedSessionId, runId, leaseDuration,
                AgentSessionType.NORMAL);
    }

    @Transactional
    public String acquire(long knowledgeBaseId,
                          String requestedSessionId,
                          String runId,
                          Duration leaseDuration,
                          AgentSessionType sessionType) {
        long leaseMicros = leaseDuration.toNanos() / 1_000L;
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            String sessionId = UUID.randomUUID().toString();
            if (sessionMapper.insertWithLease(
                    sessionId, knowledgeBaseId, sessionType, runId, leaseMicros) != 1) {
                throw new IllegalStateException("Could not create leased agent session");
            }
            return sessionId;
        }

        String sessionId = normalizeSessionId(requestedSessionId);
        AgentSessionEntity session = sessionMapper.findById(sessionId)
                .orElseThrow(() -> new AgentSessionNotFoundException(sessionId));
        if (session.getKnowledgeBaseId() == null
                || session.getKnowledgeBaseId() != knowledgeBaseId) {
            throw new AgentSessionKnowledgeBaseMismatchException(sessionId, knowledgeBaseId);
        }
        if (sessionMapper.acquireLease(
                sessionId, knowledgeBaseId, runId, leaseMicros) != 1) {
            metrics.sessionBusy();
            throw new AgentSessionBusyException(sessionId);
        }
        return sessionId;
    }

    public boolean release(String sessionId, String runId) {
        boolean released = sessionMapper.releaseLease(sessionId, runId) == 1;
        if (!released) {
            log.warn("Agent session lease release ignored for non-owner: sessionId={}, runId={}",
                    sessionId, abbreviate(runId));
        }
        return released;
    }

    static String normalizeSessionId(String rawSessionId) {
        String candidate = rawSessionId.trim().toLowerCase(Locale.ROOT);
        try {
            UUID parsed = UUID.fromString(candidate);
            if (candidate.length() != 36 || !parsed.toString().equals(candidate)) {
                throw new InvalidAgentSessionIdException();
            }
            return parsed.toString();
        } catch (IllegalArgumentException error) {
            if (error instanceof InvalidAgentSessionIdException invalid) {
                throw invalid;
            }
            throw new InvalidAgentSessionIdException();
        }
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 8 ? value : value.substring(0, 8);
    }
}
