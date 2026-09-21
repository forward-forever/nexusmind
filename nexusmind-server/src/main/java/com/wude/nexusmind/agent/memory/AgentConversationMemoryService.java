package com.wude.nexusmind.agent.memory;

import com.wude.nexusmind.agent.memory.domain.AgentMessageRole;
import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentMessageEntity;
import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentSessionEntity;
import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentMessageMapper;
import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentSessionMapper;
import com.wude.nexusmind.context.NexusTokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.wude.nexusmind.observability.NexusMindMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class AgentConversationMemoryService {

    static final int MAX_ASSISTANT_CONTENT_CHARS = 16_000;

    private static final Logger log = LoggerFactory.getLogger(AgentConversationMemoryService.class);

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final HistoricalCitationSanitizer citationSanitizer;
    private final NexusTokenEstimator tokenEstimator;
    private final NexusMindMetrics metrics;

    public AgentConversationMemoryService(AgentSessionMapper sessionMapper,
                                          AgentMessageMapper messageMapper,
                                          HistoricalCitationSanitizer citationSanitizer,
                                          NexusTokenEstimator tokenEstimator) {
        this(sessionMapper, messageMapper, citationSanitizer, tokenEstimator,
                NexusMindMetrics.noop());
    }

    @Autowired
    public AgentConversationMemoryService(AgentSessionMapper sessionMapper,
                                          AgentMessageMapper messageMapper,
                                          HistoricalCitationSanitizer citationSanitizer,
                                          NexusTokenEstimator tokenEstimator,
                                          NexusMindMetrics metrics) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.citationSanitizer = citationSanitizer;
        this.tokenEstimator = tokenEstimator;
        this.metrics = metrics;
    }

    @Transactional
    public String resolveSession(long knowledgeBaseId, String requestedSessionId) {
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            String sessionId = UUID.randomUUID().toString();
            int inserted = sessionMapper.insert(new AgentSessionEntity(sessionId, knowledgeBaseId));
            if (inserted != 1) {
                throw new IllegalStateException("Could not create agent session");
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
        return sessionId;
    }

    public List<Message> loadRecentMessages(String sessionId, int maxMessages) {
        if (maxMessages < 1) {
            throw new IllegalArgumentException("Memory maxMessages must be positive");
        }
        List<AgentMessageEntity> descending = messageMapper.findRecentBySessionId(
                sessionId, maxMessages);
        List<AgentMessageEntity> chronological = new ArrayList<>(descending);
        Collections.reverse(chronological);
        return chronological.stream().map(this::toMessage).toList();
    }

    public List<Message> loadRecentMessages(String sessionId,
                                            int maxMessages,
                                            int maxTokens) {
        if (maxMessages < 1) {
            throw new IllegalArgumentException("Memory maxMessages must be positive");
        }
        if (maxTokens < 0) {
            throw new IllegalArgumentException("Memory maxTokens must not be negative");
        }
        // 确保轮次的完整性
        int completeMessageLimit = maxMessages - maxMessages % 2;
        if (completeMessageLimit == 0 || maxTokens == 0) {
            return List.of();
        }
        // 加载最近的完整轮次
        List<AgentMessageEntity> descending = messageMapper.findRecentBySessionId(
                sessionId, completeMessageLimit);
        List<AgentMessageEntity> chronological = new ArrayList<>(descending);
        Collections.reverse(chronological);
        List<List<Message>> turns = completeTurns(chronological);
        List<List<Message>> selectedNewestFirst = new ArrayList<>();
        int usedTokens = 0;
        for (int index = turns.size() - 1; index >= 0; index--) {
            List<Message> turn = turns.get(index);
            int turnTokens = tokenEstimator.estimateMessages(turn);
            if (usedTokens + turnTokens > maxTokens) {
                break;
            }
            selectedNewestFirst.add(turn);
            usedTokens += turnTokens;
        }
        Collections.reverse(selectedNewestFirst);
        return selectedNewestFirst.stream().flatMap(List::stream).toList();
    }

    private List<List<Message>> completeTurns(List<AgentMessageEntity> chronological) {
        List<List<Message>> turns = new ArrayList<>();
        for (int index = 0; index + 1 < chronological.size(); index += 2) {
            AgentMessageEntity user = chronological.get(index);
            AgentMessageEntity assistant = chronological.get(index + 1);
            if (user.getRole() != AgentMessageRole.USER
                    || assistant.getRole() != AgentMessageRole.ASSISTANT) {
                throw new IllegalStateException("Agent memory does not contain complete turns");
            }
            // Citation sanitation happens in toMessage before token estimation.
            turns.add(List.of(toMessage(user), toMessage(assistant)));
        }
        return turns;
    }

    @Transactional
    public void appendSuccessfulTurn(String sessionId,
                                     String userContent,
                                     String assistantContent) {
        appendMessages(sessionId, userContent, assistantContent, sessionMapper.touch(sessionId));
    }

    @Transactional
    public void appendSuccessfulTurn(String sessionId,
                                     String runId,
                                     String userContent,
                                     String assistantContent) {
        if (sessionMapper.findOwnedByIdForUpdate(sessionId, runId).isEmpty()) {
            metrics.sessionLeaseLost();
            throw new AgentSessionLeaseLostException(sessionId);
        }
        appendMessages(sessionId, userContent, assistantContent,
                sessionMapper.touchOwned(sessionId, runId));
    }

    private void appendMessages(String sessionId,
                                String userContent,
                                String assistantContent,
                                int touched) {
        requireContent(userContent, 4_000, "User message");
        requireContent(assistantContent, MAX_ASSISTANT_CONTENT_CHARS, "Assistant message");
        // 保存用户消息
        int userInserted = messageMapper.insert(new AgentMessageEntity(
                sessionId, AgentMessageRole.USER, userContent));
        // 保存大模型回复消息
        int assistantInserted = messageMapper.insert(new AgentMessageEntity(
                sessionId, AgentMessageRole.ASSISTANT, assistantContent));
        if (userInserted != 1 || assistantInserted != 1 || touched != 1) {
            throw new IllegalStateException("Could not persist complete agent conversation turn");
        }
        log.info("Agent conversation turn persisted: sessionId={}, userMessages=1, assistantMessages=1",
                sessionId);
    }

    private Message toMessage(AgentMessageEntity entity) {
        return switch (entity.getRole()) {
            case USER -> new UserMessage(entity.getContent());
            case ASSISTANT -> new AssistantMessage(citationSanitizer.sanitize(entity.getContent()));
        };
    }

    private static String normalizeSessionId(String rawSessionId) {
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

    private static void requireContent(String content, int maxChars, String field) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (content.length() > maxChars) {
            throw new IllegalArgumentException(field + " exceeds " + maxChars + " characters");
        }
    }
}
