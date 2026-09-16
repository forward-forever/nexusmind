package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.api.AgentChatController;
import com.wude.nexusmind.agent.api.AgentChatRequest;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.prompt.AgentPromptFactory;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.tool.AgentToolSet;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline local integration of Controller -> ChatClient -> Spring AI tool call -> continuation -> SSE.
 * The class name keeps it outside the default Surefire test pattern.
 */
class AgentToolCallingLocalIT {

    @Test
    void executesARealSpringAiToolLoopWithoutAnyProviderOrInfrastructure() {
        ChatModel fakeChatModel = new QueueChatModel(List.of(
                Flux.just(response(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", KnowledgeSearchTool.TOOL_NAME,
                                "{\"query\":\"MVCC Read View\"}")))
                        .build())),
                Flux.just(response(new AssistantMessage("Read View 决定版本可见性。[S1]")))));

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
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(registry, properties);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        when(knowledgeBaseService.get(33L)).thenReturn(knowledgeBase);
        ToolCallingManager manager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build();
        AgentChatService service = new AgentChatService(
                knowledgeBaseService,
                new SpringAiAgentModelTurnStreamer(ChatClient.builder(fakeChatModel)),
                manager,
                new AgentToolSet(tool),
                new AgentPromptFactory(),
                properties,
                new RagChatProperties("qwen3.5-flash", 0.2, 5, 10, 12_000,
                        Duration.ofSeconds(120), Duration.ofSeconds(150)),
                Clock.systemUTC());

        List<ServerSentEvent<AgentStreamEvent>> events = new AgentChatController(service)
                .chat(33L, new AgentChatRequest("根据知识库解释 MVCC"))
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("tool_start", "tool_result", "assistant_delta", "done");
        assertThat(events.get(3).data().sources()).hasSize(1);
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
