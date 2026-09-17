package com.wude.nexusmind.agent.memory;

import com.wude.nexusmind.agent.api.AgentChatController;
import com.wude.nexusmind.agent.api.AgentChatRequest;
import com.wude.nexusmind.agent.application.AgentChatService;
import com.wude.nexusmind.agent.application.AgentModelTurnStreamer;
import com.wude.nexusmind.agent.application.DocumentContextService;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.memory.mapper.AgentMessageMapper;
import com.wude.nexusmind.agent.prompt.AgentPromptFactory;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.tool.AgentToolSet;
import com.wude.nexusmind.agent.tool.DocumentContextTool;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
class AgentConversationMemoryLocalIT {

    @Autowired
    private AgentConversationMemoryService memoryService;

    @Autowired
    private AgentSessionConcurrencyService sessionConcurrencyService;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void persistsFirstRunLoadsSecondRunAndKeepsMultiToolTraceOutOfMemory() {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
        QueueModelTurnStreamer streamer = new QueueModelTurnStreamer(List.of(
                Flux.just(text("我记住了。")),
                Flux.just(toolCalls(searchCall("call-1", "MVCC Read View"))),
                Flux.just(toolCalls(contextCall("call-2", "S1"))),
                Flux.just(text("临时代号是蓝鲸47，相关上下文也已查阅。[S1]"))));
        AgentChatService service = service(streamer);
        AgentChatController controller = new AgentChatController(service);
        String sessionId = null;
        try {
            List<ServerSentEvent<AgentStreamEvent>> first = controller.chat(
                            33L, new AgentChatRequest(null, "请记住临时代号是蓝鲸47。"))
                    .collectList().block(Duration.ofSeconds(3));
            assertThat(first).isNotNull();
            AgentStreamEvent firstDone = first.get(first.size() - 1).data();
            assertThat(firstDone.type()).isEqualTo("done");
            sessionId = firstDone.sessionId();
            assertThat(sessionId).isNotBlank();

            List<ServerSentEvent<AgentStreamEvent>> second = controller.chat(
                            33L, new AgentChatRequest(sessionId, "代号是什么？并查询相关上下文。"))
                    .collectList().block(Duration.ofSeconds(3));
            assertThat(second).isNotNull();
            AgentStreamEvent secondDone = second.get(second.size() - 1).data();
            assertThat(secondDone.sessionId()).isEqualTo(sessionId);
            assertThat(secondDone.runId()).isNotEqualTo(firstDone.runId());
            assertThat(secondDone.toolCallCount()).isEqualTo(2);
            assertThat(secondDone.modelTurnCount()).isEqualTo(3);

            assertThat(streamer.prompts.get(1).getInstructions())
                    .extracting(message -> message.getText())
                    .containsSubsequence(
                            "请记住临时代号是蓝鲸47。",
                            "我记住了。",
                            "代号是什么？并查询相关上下文。");
            assertThat(messageMapper.findRecentBySessionId(sessionId, 20))
                    .extracting(message -> message.getRole().name())
                    .containsExactly("ASSISTANT", "USER", "ASSISTANT", "USER");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_message WHERE session_id = ?",
                    Integer.class, sessionId)).isEqualTo(4);
        } finally {
            if (sessionId != null) {
                jdbcTemplate.update("DELETE FROM agent_message WHERE session_id = ?", sessionId);
                jdbcTemplate.update("DELETE FROM agent_session WHERE session_id = ?", sessionId);
            }
        }
    }

    private AgentChatService service(QueueModelTurnStreamer streamer) {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.type()).thenReturn(RetrieverType.DENSE);
        when(retrieval.retrieve(33L, "MVCC Read View", 5)).thenReturn(new RetrievalResult(
                "MVCC Read View", 33L, "embedding", 1024, RetrieverType.DENSE,
                RetrievalScoreType.COSINE, 5, List.of(new RetrievalHit(
                100L, 10L, "mysql.pdf", 1, 0.9, RetrievalScoreType.COSINE,
                "target", 2, "Read View"))));
        AgentProperties properties = new AgentProperties(
                true, 5, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                new AgentProperties.DocumentContext(1, 1),
                new AgentProperties.Memory(12));
        KnowledgeSearchTool searchTool = new KnowledgeSearchTool(
                new RetrievalServiceRegistry(List.of(retrieval)), properties);
        KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunk target = chunk(100L, 1, "target");
        when(chunks.findById(100L)).thenReturn(Optional.of(target));
        when(chunks.findByDocumentIdAndChunkIndexBetween(10L, 0, 2)).thenReturn(List.of(
                chunk(99L, 0, "before"), target, chunk(101L, 2, "after")));
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(10L);
        document.setKnowledgeBaseId(33L);
        document.setOriginalFileName("mysql.pdf");
        document.setStatus(DocumentStatus.READY);
        document.setIndexStatus(DocumentIndexStatus.INDEXED);
        when(documents.findById(10L)).thenReturn(Optional.of(document));
        DocumentContextTool contextTool = new DocumentContextTool(
                new DocumentContextService(chunks, documents), properties);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(33L);
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        when(knowledgeBases.get(33L)).thenReturn(knowledgeBase);
        ToolCallingManager manager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build();
        return new AgentChatService(
                knowledgeBases, streamer, manager, new AgentToolSet(searchTool, contextTool),
                new AgentPromptFactory(), memoryService, sessionConcurrencyService, properties,
                new RagChatProperties("qwen3.5-flash", 0.2, 5, 10, 12_000,
                        Duration.ofSeconds(120), Duration.ofSeconds(150)),
                Clock.systemUTC(),
                com.wude.nexusmind.resilience.ProviderStreamingRetry.noRetry());
    }

    private static KnowledgeChunk chunk(long id, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                33L, 10L, index, content, index + 1, "Read View", content.length(), null);
        chunk.setId(id);
        return chunk;
    }

    private static AssistantMessage.ToolCall searchCall(String id, String query) {
        return new AssistantMessage.ToolCall(
                id, "function", KnowledgeSearchTool.TOOL_NAME,
                "{\"query\":\"" + query + "\"}");
    }

    private static AssistantMessage.ToolCall contextCall(String id, String sourceId) {
        return new AssistantMessage.ToolCall(
                id, "function", DocumentContextTool.TOOL_NAME,
                "{\"sourceId\":\"" + sourceId + "\"}");
    }

    private static ChatClientResponse toolCalls(AssistantMessage.ToolCall call) {
        return response(AssistantMessage.builder().content("").toolCalls(List.of(call)).build());
    }

    private static ChatClientResponse text(String content) {
        return response(new AssistantMessage(content));
    }

    private static ChatClientResponse response(AssistantMessage message) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(message))), Map.of());
    }

    private static final class QueueModelTurnStreamer implements AgentModelTurnStreamer {

        private final Deque<Flux<ChatClientResponse>> turns;
        private final List<Prompt> prompts = new ArrayList<>();

        private QueueModelTurnStreamer(List<Flux<ChatClientResponse>> turns) {
            this.turns = new ArrayDeque<>(turns);
        }

        @Override
        public Flux<ChatClientResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return turns.isEmpty()
                    ? Flux.error(new IllegalStateException("No fake model turn available"))
                    : turns.removeFirst();
        }
    }
}
