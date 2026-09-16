package com.wude.nexusmind.agent.application;

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
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatServiceTest {

    @Test
    void directAnswerStreamsWithoutCallingTool() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(text("你"), text("好")));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "你好，你是谁？")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("assistant_delta", "assistant_delta", "done");
        assertThat(events.get(2).toolCallCount()).isZero();
        assertThat(events.get(2).modelTurnCount()).isEqualTo(1);
        verify(fixture.retrieval, org.mockito.Mockito.never()).retrieve(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void oneToolCallUsesSpringConversationHistoryThenStreamsFinalAnswer() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(call("call-1", "MVCC Read View"))),
                Flux.just(text("Read View "), text("决定可见性。[S1]")));

        List<AgentStreamEvent> events = fixture.service.chat(
                        33L, "根据当前知识库解释 MVCC 的 Read View。")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result",
                        "assistant_delta", "assistant_delta", "done");
        AgentStreamEvent done = events.get(events.size() - 1);
        assertThat(done.toolCallCount()).isEqualTo(1);
        assertThat(done.modelTurnCount()).isEqualTo(2);
        assertThat(done.sources()).extracting(source -> source.sourceId()).containsExactly("S1");
        assertThat(fixture.streamer.prompts).hasSize(2);
        assertThat(fixture.streamer.prompts.get(1).getInstructions())
                .anyMatch(ToolResponseMessage.class::isInstance);
    }

    @Test
    void executesMultipleToolCallsAndReusesSourcesWithinOneRun() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(
                        call("call-1", "MVCC"),
                        call("call-2", "Read View"))),
                Flux.just(text("answer [S1]")));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "compare")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result", "tool_start", "tool_result",
                        "assistant_delta", "done");
        assertThat(events.get(events.size() - 1).toolCallCount()).isEqualTo(2);
        assertThat(events.get(events.size() - 1).sources())
                .extracting(source -> source.sourceId()).containsExactly("S1");
        verify(fixture.retrieval, times(2)).retrieve(
                org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    void rejectsWholeToolBatchWhenItWouldExceedLimit() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(
                        call("call-1", "q1"), call("call-2", "q2"),
                        call("call-3", "q3"), call("call-4", "q4"))),
                Flux.just(toolCalls(call("call-5", "q5"), call("call-6", "q6"))));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "search repeatedly")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).filteredOn(event -> "tool_start".equals(event.type())).hasSize(4);
        assertThat(events.get(events.size() - 1).type()).isEqualTo("error");
        assertThat(events.get(events.size() - 1).code())
                .isEqualTo("AGENT_TOOL_LIMIT_EXCEEDED");
        verify(fixture.retrieval, times(4)).retrieve(
                org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    void absoluteDeadlineStopsBeforeToolExecution() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T00:00:00Z"));
        Flux<ChatClientResponse> lateToolCall = Flux.defer(() -> {
            clock.advance(Duration.ofSeconds(31));
            return Flux.just(toolCalls(call("call-1", "MVCC")));
        });
        Fixture fixture = fixture(5, clock, false, lateToolCall);

        List<AgentStreamEvent> events = fixture.service.chat(33L, "search")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type).containsExactly("error");
        assertThat(events.get(0).code()).isEqualTo("AGENT_TIMEOUT");
        verify(fixture.retrieval, org.mockito.Mockito.never()).retrieve(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void toolFailurePublishesToolErrorThenRunErrorWithoutFallback() {
        Fixture fixture = fixture(5, Clock.systemUTC(), true,
                Flux.just(toolCalls(call("call-1", "MVCC"))));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "search")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_error", "error");
        assertThat(events.get(2).code()).isEqualTo("AGENT_TOOL_ERROR");
        assertThat(events).noneMatch(event -> "done".equals(event.type()));
    }

    @SafeVarargs
    private static Fixture fixture(int maxToolCalls,
                                   Clock clock,
                                   boolean retrievalFails,
                                   Flux<ChatClientResponse>... turns) {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.type()).thenReturn(RetrieverType.DENSE);
        if (retrievalFails) {
            when(retrieval.retrieve(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenThrow(new IllegalStateException("retrieval failed"));
        } else {
            when(retrieval.retrieve(org.mockito.ArgumentMatchers.eq(33L),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(5)))
                    .thenAnswer(invocation -> new RetrievalResult(
                            invocation.getArgument(1), 33L, "embedding", 1024,
                            RetrieverType.DENSE, RetrievalScoreType.COSINE, 5,
                            List.of(hit())));
        }
        RetrievalServiceRegistry registry = new RetrievalServiceRegistry(List.of(retrieval));
        AgentProperties properties = new AgentProperties(
                true, maxToolCalls, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(registry, properties);
        QueueModelTurnStreamer streamer = new QueueModelTurnStreamer(List.of(turns));
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(33L);
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        when(knowledgeBaseService.get(33L)).thenReturn(knowledgeBase);
        AgentChatService service = new AgentChatService(
                knowledgeBaseService,
                streamer,
                toolCallingManager(),
                new AgentToolSet(tool),
                new AgentPromptFactory(),
                properties,
                new RagChatProperties("qwen3.5-flash", 0.2, 5, 10, 12_000,
                        Duration.ofSeconds(120), Duration.ofSeconds(150)),
                clock);
        return new Fixture(service, retrieval, streamer);
    }

    private static ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build();
    }

    private static RetrievalHit hit() {
        return new RetrievalHit(100L, 10L, "mysql.pdf", 0, 0.9,
                RetrievalScoreType.COSINE, "MVCC content", 17, "Read View");
    }

    private static AssistantMessage.ToolCall call(String id, String query) {
        return new AssistantMessage.ToolCall(
                id, "function", KnowledgeSearchTool.TOOL_NAME,
                "{\"query\":\"" + query + "\"}");
    }

    private static ChatClientResponse toolCalls(AssistantMessage.ToolCall... calls) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(calls))
                .build();
        return response(message);
    }

    private static ChatClientResponse text(String content) {
        return response(new AssistantMessage(content));
    }

    private static ChatClientResponse response(AssistantMessage message) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(message))), Map.of());
    }

    private record Fixture(AgentChatService service,
                           RetrievalService retrieval,
                           QueueModelTurnStreamer streamer) {
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
            if (turns.isEmpty()) {
                return Flux.error(new IllegalStateException("No fake model turn available"));
            }
            return turns.removeFirst();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
