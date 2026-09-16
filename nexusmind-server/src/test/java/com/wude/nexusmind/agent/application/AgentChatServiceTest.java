package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.config.AgentProperties;
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
import java.util.Optional;

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
    void modelDrivesSearchThenContextThenFinalAnswerAcrossThreeTurns() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(call("call-1", "MVCC Read View"))),
                Flux.just(toolCalls(contextCall("call-2", "S1"))),
                Flux.just(text("RC 每次读取创建 Read View，RR 通常复用事务级 Read View。[S1][S3]")));
        fixture.stubVisibleContext();

        List<AgentStreamEvent> events = fixture.service.chat(33L, "搜索并查看附近上下文")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly(
                        "tool_start", "tool_result",
                        "tool_start", "tool_result",
                        "assistant_delta", "done");
        assertThat(events).filteredOn(event -> "tool_start".equals(event.type()))
                .extracting(AgentStreamEvent::toolName)
                .containsExactly(KnowledgeSearchTool.TOOL_NAME, DocumentContextTool.TOOL_NAME);
        AgentStreamEvent contextStart = events.get(2);
        assertThat(contextStart.arguments()).containsExactly(Map.entry("sourceId", "S1"));
        AgentStreamEvent done = events.get(events.size() - 1);
        assertThat(done.toolCallCount()).isEqualTo(2);
        assertThat(done.modelTurnCount()).isEqualTo(3);
        assertThat(done.sources()).extracting(source -> source.sourceId())
                .containsExactly("S1", "S2", "S3");
        assertThat(fixture.streamer.prompts).hasSize(3);
    }

    @Test
    void unknownContextSourceReturnsToolResultAndAllowsNextModelTurn() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(contextCall("call-1", "S99"))),
                Flux.just(text("该来源在当前运行中不可用。")));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "expand S99")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result", "assistant_delta", "done");
        assertThat(events.get(1).resultCount()).isZero();
        assertThat(events.get(3).toolCallCount()).isEqualTo(1);
        assertThat(events.get(3).modelTurnCount()).isEqualTo(2);
    }

    @Test
    void contextInfrastructureFailurePublishesToolErrorThenRunError() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(call("call-1", "MVCC"))),
                Flux.just(toolCalls(contextCall("call-2", "S1"))));
        when(fixture.chunks.findById(100L)).thenThrow(new IllegalStateException("database down"));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "search then expand")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result", "tool_start", "tool_error", "error");
        assertThat(events.get(4).code()).isEqualTo("AGENT_TOOL_ERROR");
        assertThat(events).noneMatch(event -> "done".equals(event.type()));
    }

    @Test
    void rejectsWholeToolBatchWhenItWouldExceedLimit() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(
                        call("call-1", "q1"), call("call-2", "q2"),
                        call("call-3", "q3"), call("call-4", "q4"))),
                Flux.just(toolCalls(call("call-5", "q5"), contextCall("call-6", "S1"))));

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
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                new AgentProperties.DocumentContext(1, 1));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(registry, properties);
        KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        DocumentContextTool contextTool = new DocumentContextTool(
                new DocumentContextService(chunks, documents), properties);
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
                new AgentToolSet(tool, contextTool),
                new AgentPromptFactory(),
                properties,
                new RagChatProperties("qwen3.5-flash", 0.2, 5, 10, 12_000,
                        Duration.ofSeconds(120), Duration.ofSeconds(150)),
                clock);
        return new Fixture(service, retrieval, streamer, chunks, documents);
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

    private static AssistantMessage.ToolCall contextCall(String id, String sourceId) {
        return new AssistantMessage.ToolCall(
                id, "function", DocumentContextTool.TOOL_NAME,
                "{\"sourceId\":\"" + sourceId + "\"}");
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
                           QueueModelTurnStreamer streamer,
                           KnowledgeChunkMapper chunks,
                           KnowledgeDocumentMapper documents) {

        void stubVisibleContext() {
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
        }
    }

    private static KnowledgeChunk chunk(long id, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                33L, 10L, index, content, index + 1, "Read View", content.length(), null);
        chunk.setId(id);
        return chunk;
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
