package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.api.AgentChatController;
import com.wude.nexusmind.agent.api.AgentChatRequest;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.memory.AgentConversationMemoryService;
import com.wude.nexusmind.agent.prompt.AgentPromptFactory;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.tool.AgentToolSet;
import com.wude.nexusmind.agent.tool.DocumentContextTool;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline local integration of Controller -> ChatClient -> Spring AI tool call -> continuation -> SSE.
 * The class name keeps it outside the default Surefire test pattern.
 */
class AgentToolCallingLocalIT {

    @Test
    void executesSearchThenContextThroughRealSpringAiToolLoopAndHttpSse() {
        ChatModel fakeChatModel = new QueueChatModel(List.of(
                Flux.just(response(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", KnowledgeSearchTool.TOOL_NAME,
                                "{\"query\":\"MVCC Read View\"}")))
                        .build())),
                Flux.just(response(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-2", "function", DocumentContextTool.TOOL_NAME,
                                "{\"sourceId\":\"S1\"}")))
                        .build())),
                Flux.just(response(new AssistantMessage(
                        "Read View 在 RC 与 RR 中的创建时机不同。[S1][S3]")))));

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.type()).thenReturn(RetrieverType.DENSE);
        when(retrieval.retrieve(33L, "MVCC Read View", 5)).thenReturn(new RetrievalResult(
                "MVCC Read View", 33L, "embedding", 1024, RetrieverType.DENSE,
                RetrievalScoreType.COSINE, 5, List.of(new RetrievalHit(
                100L, 10L, "mysql.pdf", 0, 0.9, RetrievalScoreType.COSINE,
                "MVCC content", 17, "Read View"))));
        RetrievalServiceRegistry registry = new RetrievalServiceRegistry(List.of(retrieval));
        AgentProperties properties = new AgentProperties(
                true, 5, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                new AgentProperties.DocumentContext(1, 1),
                new AgentProperties.Memory(12));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(registry, properties);
        KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunk target = chunk(100L, 1, "target");
        when(chunks.findById(100L)).thenReturn(Optional.of(target));
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(10L);
        document.setKnowledgeBaseId(33L);
        document.setOriginalFileName("mysql.pdf");
        document.setStatus(DocumentStatus.READY);
        document.setIndexStatus(DocumentIndexStatus.INDEXED);
        when(documents.findById(10L)).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdAndChunkIndexBetween(10L, 0, 2)).thenReturn(List.of(
                chunk(99L, 0, "before"), target, chunk(101L, 2, "after")));
        DocumentContextTool contextTool = new DocumentContextTool(
                new DocumentContextService(chunks, documents), properties);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        when(knowledgeBaseService.get(33L)).thenReturn(knowledgeBase);
        AgentConversationMemoryService memory = mock(AgentConversationMemoryService.class);
        com.wude.nexusmind.agent.memory.AgentSessionConcurrencyService concurrency =
                mock(com.wude.nexusmind.agent.memory.AgentSessionConcurrencyService.class);
        when(concurrency.acquire(org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn("11111111-1111-1111-1111-111111111111");
        when(memory.loadRecentMessages("11111111-1111-1111-1111-111111111111", 12))
                .thenReturn(List.of());
        ToolCallingManager manager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build();
        AgentChatService service = new AgentChatService(
                knowledgeBaseService,
                new SpringAiAgentModelTurnStreamer(ChatClient.builder(fakeChatModel)),
                manager,
                new AgentToolSet(tool, contextTool),
                new AgentPromptFactory(),
                memory,
                concurrency,
                properties,
                new RagChatProperties("qwen3.5-flash", 0.2, 5, 10, 12_000,
                        Duration.ofSeconds(120), Duration.ofSeconds(150)),
                Clock.systemUTC(),
                com.wude.nexusmind.resilience.ProviderStreamingRetry.noRetry());

        List<ServerSentEvent<AgentStreamEvent>> events = new AgentChatController(service)
                .chat(33L, new AgentChatRequest(null, "根据知识库解释 MVCC"))
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly(
                        "tool_start", "tool_result", "tool_start", "tool_result",
                        "assistant_delta", "done");
        assertThat(events.get(2).data().toolName()).isEqualTo(DocumentContextTool.TOOL_NAME);
        assertThat(events.get(5).data().sources()).hasSize(3);
        assertThat(events.get(5).data().toolCallCount()).isEqualTo(2);
        assertThat(events.get(5).data().modelTurnCount()).isEqualTo(3);
    }

    private static KnowledgeChunk chunk(long id, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                33L, 10L, index, content, index + 1, "Read View", content.length(), null);
        chunk.setId(id);
        return chunk;
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static final class QueueChatModel implements ChatModel {

        private final Deque<Flux<ChatResponse>> turns;

        private QueueChatModel(List<Flux<ChatResponse>> turns) {
            this.turns = new ArrayDeque<>(turns);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("This fixture verifies the streaming path");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return turns.isEmpty()
                    ? Flux.error(new IllegalStateException("No fake turn available"))
                    : turns.removeFirst();
        }
    }
}
