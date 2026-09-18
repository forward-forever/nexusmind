package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.memory.AgentConversationMemoryService;
import com.wude.nexusmind.agent.memory.AgentSessionKnowledgeBaseMismatchException;
import com.wude.nexusmind.agent.memory.AgentSessionNotFoundException;
import com.wude.nexusmind.agent.memory.AgentSessionBusyException;
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
import com.wude.nexusmind.support.TestTokenSupport;
import com.wude.nexusmind.context.TokenBudgetCalculator;
import com.wude.nexusmind.context.TokenBudgetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
        assertThat(events).extracting(AgentStreamEvent::sessionId)
                .containsOnly("11111111-1111-1111-1111-111111111111");
        verify(fixture.memory).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.eq("11111111-1111-1111-1111-111111111111"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("你好，你是谁？"),
                org.mockito.ArgumentMatchers.eq("你好"));
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
        verify(fixture.memory).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.eq("11111111-1111-1111-1111-111111111111"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("根据当前知识库解释 MVCC 的 Read View。"),
                org.mockito.ArgumentMatchers.eq("Read View 决定可见性。[S1]"));
    }

    @Test
    void persistsOnlyFinalModelTurnInsteadOfPreToolNarration() {
        AssistantMessage preTool = AssistantMessage.builder()
                .content("我先搜索一下。")
                .toolCalls(List.of(call("call-1", "MVCC")))
                .build();
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(response(preTool)),
                Flux.just(text("最终回答。[S1]")));

        fixture.service.chat(33L, "question").collectList().block(Duration.ofSeconds(2));

        verify(fixture.memory).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.eq("11111111-1111-1111-1111-111111111111"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq("最终回答。[S1]"));
    }

    @Test
    void unknownOrCrossKnowledgeBaseSessionFailsBeforeModelInvocation() {
        String unknown = "33333333-3333-3333-3333-333333333333";
        Fixture unknownFixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(text("must not execute")));
        when(unknownFixture.memory.resolveSession(33L, unknown))
                .thenThrow(new AgentSessionNotFoundException(unknown));

        assertThatThrownBy(() -> unknownFixture.service.chat(33L, unknown, "question").blockLast())
                .isInstanceOf(AgentSessionNotFoundException.class);
        assertThat(unknownFixture.streamer.prompts).isEmpty();

        String foreign = "44444444-4444-4444-4444-444444444444";
        Fixture crossKbFixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(text("must not execute")));
        when(crossKbFixture.memory.resolveSession(33L, foreign))
                .thenThrow(new AgentSessionKnowledgeBaseMismatchException(foreign, 33L));

        assertThatThrownBy(() -> crossKbFixture.service.chat(33L, foreign, "question").blockLast())
                .isInstanceOf(AgentSessionKnowledgeBaseMismatchException.class);
        assertThat(crossKbFixture.streamer.prompts).isEmpty();
    }

    @Test
    void busySessionEmitsExplicitErrorWithoutInvokingModel() {
        String sessionId = "22222222-2222-2222-2222-222222222222";
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(text("must not execute")));
        when(fixture.concurrency.acquire(org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AgentSessionBusyException(sessionId));

        List<AgentStreamEvent> events = fixture.service.chat(33L, sessionId, "question")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).code()).isEqualTo("AGENT_SESSION_BUSY");
        assertThat(fixture.streamer.prompts).isEmpty();
    }

    @Test
    void retriesAgentModelTurnBeforeDeltaAndToolExecutionWithoutIncrementingLogicalTurn() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                retryingStreamingPolicy(),
                Flux.error(new IllegalStateException(new SocketTimeoutException("temporary"))),
                Flux.just(toolCalls(call("call-1", "MVCC"))),
                Flux.just(text("answer [S1]")));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "question")
                .collectList().block(Duration.ofSeconds(2));

        AgentStreamEvent done = events.get(events.size() - 1);
        assertThat(done.type()).isEqualTo("done");
        assertThat(done.modelTurnCount()).isEqualTo(2);
        assertThat(done.toolCallCount()).isEqualTo(1);
        assertThat(fixture.streamer.prompts).hasSize(3);
    }

    @Test
    void doesNotRetryAgentModelTurnAfterAssistantDeltaWasPublished() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                retryingStreamingPolicy(),
                Flux.concat(Flux.just(text("partial")),
                        Flux.error(new IllegalStateException(new SocketTimeoutException("temporary")))),
                Flux.just(text("must not replay")));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "question")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("assistant_delta", "error");
        assertThat(fixture.streamer.prompts).hasSize(1);
    }

    @Test
    void doesNotRetryLaterModelTurnAfterToolExecutionStarted() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                retryingStreamingPolicy(),
                Flux.just(toolCalls(call("call-1", "MVCC"))),
                Flux.error(new IllegalStateException(new SocketTimeoutException("temporary"))),
                Flux.just(text("must not replay")));

        List<AgentStreamEvent> events = fixture.service.chat(33L, "question")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result", "error");
        assertThat(fixture.streamer.prompts).hasSize(2);
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
        verify(fixture.memory).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.eq("11111111-1111-1111-1111-111111111111"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("搜索并查看附近上下文"),
                org.mockito.ArgumentMatchers.eq("RC 每次读取创建 Read View，RR 通常复用事务级 Read View。[S1][S3]"));
    }

    @Test
    void sameSessionLoadsHistoryBeforeCurrentUserAndDoesNotDuplicateItAcrossToolTurns() {
        String sessionId = "22222222-2222-2222-2222-222222222222";
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(toolCalls(call("call-1", "RR Read View"))),
                Flux.just(toolCalls(contextCall("call-2", "S1"))),
                Flux.just(text("第二种隔离级别是 RR。[S1]")));
        fixture.stubVisibleContext();
        when(fixture.memory.resolveSession(33L, sessionId)).thenReturn(sessionId);
        when(fixture.memory.loadRecentMessages(sessionId, 12, 6000)).thenReturn(List.of(
                new UserMessage("请解释 RC 和 RR。"),
                new AssistantMessage("第一种是 RC，第二种是 RR。")));

        List<AgentStreamEvent> events = fixture.service.chat(
                        33L, sessionId, "你刚才说的第二种是什么？")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events.get(events.size() - 1).modelTurnCount()).isEqualTo(3);
        assertThat(fixture.streamer.prompts).hasSize(3);
        assertThat(fixture.streamer.prompts.get(0).getInstructions())
                .extracting(message -> message.getText())
                .containsExactly(
                        new AgentPromptFactory().systemPrompt(),
                        "请解释 RC 和 RR。",
                        "第一种是 RC，第二种是 RR。",
                        "你刚才说的第二种是什么？");
        long historicalQuestionOccurrences = fixture.streamer.prompts.get(2).getInstructions().stream()
                .filter(message -> "请解释 RC 和 RR。".equals(message.getText()))
                .count();
        assertThat(historicalQuestionOccurrences).isEqualTo(1);
        verify(fixture.memory).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("你刚才说的第二种是什么？"),
                org.mockito.ArgumentMatchers.eq("第二种隔离级别是 RR。[S1]"));
    }

    @Test
    void finalPromptValidationRejectsAccumulatedToolProtocolBeforeAnotherModelCallAndReleasesLease() {
        AgentPromptFactory promptFactory = new AgentPromptFactory();
        int fixedTokens = TestTokenSupport.estimator().estimateMessages(
                promptFactory.create(List.of(), "search"));
        TokenBudgetCalculator tightBudget = TestTokenSupport.calculator(
                new TokenBudgetProperties(fixedTokens + 55, 1, 1, 3));
        Fixture fixture = fixture(
                5, Clock.systemUTC(), false,
                com.wude.nexusmind.resilience.ProviderStreamingRetry.noRetry(), tightBudget,
                Flux.just(toolCalls(call("call-1", "MVCC"))));

        List<AgentStreamEvent> events = fixture.service.chat(33L, null, "search")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result", "error");
        assertThat(events.get(events.size() - 1).code())
                .isEqualTo("AGENT_CONTEXT_BUDGET_EXCEEDED");
        assertThat(fixture.streamer.prompts).hasSize(1);
        verify(fixture.memory, never()).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(fixture.concurrency).release(
                org.mockito.ArgumentMatchers.eq("11111111-1111-1111-1111-111111111111"),
                org.mockito.ArgumentMatchers.anyString());
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
        verify(fixture.memory, never()).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
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
        verify(fixture.memory, never()).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
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
        verify(fixture.memory, never()).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
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
        verify(fixture.memory, never()).appendSuccessfulTurn(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void persistenceFailureEmitsErrorAfterDeltaAndNeverDone() {
        Fixture fixture = fixture(5, Clock.systemUTC(), false,
                Flux.just(text("完整回答")));
        doThrow(new IllegalStateException("memory write failed"))
                .when(fixture.memory).appendSuccessfulTurn(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        List<AgentStreamEvent> events = fixture.service.chat(33L, "question")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("assistant_delta", "error");
        assertThat(events).noneMatch(event -> "done".equals(event.type()));
    }

    @SafeVarargs
    private static Fixture fixture(int maxToolCalls,
                                   Clock clock,
                                   boolean retrievalFails,
                                   Flux<ChatClientResponse>... turns) {
        return fixture(maxToolCalls, clock, retrievalFails,
                com.wude.nexusmind.resilience.ProviderStreamingRetry.noRetry(), turns);
    }

    @SafeVarargs
    private static Fixture fixture(int maxToolCalls,
                                   Clock clock,
                                   boolean retrievalFails,
                                   com.wude.nexusmind.resilience.ProviderStreamingRetry streamingRetry,
                                   Flux<ChatClientResponse>... turns) {
        return fixture(maxToolCalls, clock, retrievalFails, streamingRetry,
                TestTokenSupport.calculator(), turns);
    }

    @SafeVarargs
    private static Fixture fixture(int maxToolCalls,
                                   Clock clock,
                                   boolean retrievalFails,
                                   com.wude.nexusmind.resilience.ProviderStreamingRetry streamingRetry,
                                   TokenBudgetCalculator tokenBudgetCalculator,
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
                new AgentProperties.DocumentContext(1, 1),
                new AgentProperties.Memory(12));
        AgentToolResultBudgeter toolBudgeter = TestTokenSupport.toolBudgeter(properties);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(registry, properties, toolBudgeter);
        KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        DocumentContextTool contextTool = new DocumentContextTool(
                new DocumentContextService(chunks, documents, toolBudgeter), properties);
        QueueModelTurnStreamer streamer = new QueueModelTurnStreamer(List.of(turns));
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(33L);
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        when(knowledgeBaseService.get(33L)).thenReturn(knowledgeBase);
        AgentConversationMemoryService memory = mock(AgentConversationMemoryService.class);
        when(memory.resolveSession(33L, null))
                .thenReturn("11111111-1111-1111-1111-111111111111");
        com.wude.nexusmind.agent.memory.AgentSessionConcurrencyService concurrency =
                mock(com.wude.nexusmind.agent.memory.AgentSessionConcurrencyService.class);
        when(concurrency.acquire(org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                memory.resolveSession(33L, invocation.getArgument(1)));
        when(memory.loadRecentMessages("11111111-1111-1111-1111-111111111111", 12, 6000))
                .thenReturn(List.of());
        AgentChatService service = new AgentChatService(
                knowledgeBaseService,
                streamer,
                toolCallingManager(),
                new AgentToolSet(tool, contextTool),
                new AgentPromptFactory(),
                memory,
                concurrency,
                properties,
                new RagChatProperties("qwen3.5-flash", 0.2, 5, 10, 12_000,
                        Duration.ofSeconds(120), Duration.ofSeconds(150)),
                clock,
                streamingRetry,
                tokenBudgetCalculator);
        return new Fixture(service, retrieval, streamer, chunks, documents, memory, concurrency);
    }

    private static com.wude.nexusmind.resilience.ProviderStreamingRetry retryingStreamingPolicy() {
        return new com.wude.nexusmind.resilience.ProviderStreamingRetry(
                new com.wude.nexusmind.resilience.AiResilienceProperties(
                        2, Duration.ZERO, 1.0, Duration.ofNanos(1), Duration.ZERO),
                new com.wude.nexusmind.resilience.ProviderFailureClassifier());
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
                           KnowledgeDocumentMapper documents,
                           AgentConversationMemoryService memory,
                           com.wude.nexusmind.agent.memory.AgentSessionConcurrencyService concurrency) {

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
