package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.memory.AgentConversationMemoryService;
import com.wude.nexusmind.agent.memory.AgentSessionBusyException;
import com.wude.nexusmind.agent.memory.AgentSessionConcurrencyService;
import com.wude.nexusmind.agent.memory.AgentSessionLeaseLostException;
import com.wude.nexusmind.agent.memory.domain.AgentSessionType;
import com.wude.nexusmind.agent.mcp.McpToolExecutionException;
import com.wude.nexusmind.agent.prompt.AgentPromptFactory;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.stream.AgentToolEventPublisher;
import com.wude.nexusmind.agent.tool.AgentToolSet;
import com.wude.nexusmind.agent.tool.AgentToolCatalog;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
import com.wude.nexusmind.resilience.ProviderStreamingRetry;
import com.wude.nexusmind.context.ContextBudgetExceededException;
import com.wude.nexusmind.context.TokenBudgetCalculator;
import com.wude.nexusmind.observability.NexusMindMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentModelTurnStreamer modelTurnStreamer;
    private final ToolCallingManager toolCallingManager;
    private final AgentToolCatalog toolCatalog;
    private final AgentPromptFactory promptFactory;
    private final AgentConversationMemoryService memoryService;
    private final AgentSessionConcurrencyService sessionConcurrencyService;
    private final AgentProperties properties;
    private final RagChatProperties chatProperties;
    private final Clock clock;
    private final ProviderStreamingRetry streamingRetry;
    private final TokenBudgetCalculator tokenBudgetCalculator;
    private final AgentCitationValidator citationValidator;
    private final NexusMindMetrics metrics;

    @Autowired
    public AgentChatService(KnowledgeBaseService knowledgeBaseService,
                            AgentModelTurnStreamer modelTurnStreamer,
                            @Qualifier("agentToolCallingManager") ToolCallingManager toolCallingManager,
                            AgentToolCatalog toolCatalog,
                            AgentPromptFactory promptFactory,
                            AgentConversationMemoryService memoryService,
                            AgentSessionConcurrencyService sessionConcurrencyService,
                            AgentProperties properties,
                            RagChatProperties chatProperties,
                            Clock clock,
                            ProviderStreamingRetry streamingRetry,
                            TokenBudgetCalculator tokenBudgetCalculator,
                            AgentCitationValidator citationValidator,
                            NexusMindMetrics metrics) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelTurnStreamer = modelTurnStreamer;
        this.toolCallingManager = toolCallingManager;
        this.toolCatalog = toolCatalog;
        this.promptFactory = promptFactory;
        this.memoryService = memoryService;
        this.sessionConcurrencyService = sessionConcurrencyService;
        this.properties = properties;
        this.chatProperties = chatProperties;
        this.clock = clock;
        this.streamingRetry = streamingRetry;
        this.tokenBudgetCalculator = tokenBudgetCalculator;
        this.citationValidator = citationValidator;
        this.metrics = metrics;
    }

    public AgentChatService(KnowledgeBaseService knowledgeBaseService,
                            AgentModelTurnStreamer modelTurnStreamer,
                            ToolCallingManager toolCallingManager,
                            AgentToolCatalog toolCatalog,
                            AgentPromptFactory promptFactory,
                            AgentConversationMemoryService memoryService,
                            AgentSessionConcurrencyService sessionConcurrencyService,
                            AgentProperties properties,
                            RagChatProperties chatProperties,
                            Clock clock,
                            ProviderStreamingRetry streamingRetry,
                            TokenBudgetCalculator tokenBudgetCalculator) {
        this(knowledgeBaseService, modelTurnStreamer, toolCallingManager, toolCatalog,
                promptFactory, memoryService, sessionConcurrencyService, properties,
                chatProperties, clock, streamingRetry, tokenBudgetCalculator,
                new AgentCitationValidator(), NexusMindMetrics.noop());
    }

    public AgentChatService(KnowledgeBaseService knowledgeBaseService,
                            AgentModelTurnStreamer modelTurnStreamer,
                            ToolCallingManager toolCallingManager,
                            AgentToolSet toolSet,
                            AgentPromptFactory promptFactory,
                            AgentConversationMemoryService memoryService,
                            AgentSessionConcurrencyService sessionConcurrencyService,
                            AgentProperties properties,
                            RagChatProperties chatProperties,
                            Clock clock,
                            ProviderStreamingRetry streamingRetry,
                            TokenBudgetCalculator tokenBudgetCalculator) {
        this(knowledgeBaseService, modelTurnStreamer, toolCallingManager,
                AgentToolCatalog.nativeOnly(toolSet), promptFactory, memoryService,
                sessionConcurrencyService, properties, chatProperties, clock,
                streamingRetry, tokenBudgetCalculator, new AgentCitationValidator(),
                NexusMindMetrics.noop());
    }

    public Flux<AgentStreamEvent> chat(long knowledgeBaseId, String message) {
        return chat(knowledgeBaseId, null, message);
    }

    public Flux<AgentStreamEvent> chat(long knowledgeBaseId,
                                       String requestedSessionId,
                                       String message) {
        return chat(knowledgeBaseId, requestedSessionId, message, AgentSessionType.NORMAL);
    }

    public Flux<AgentStreamEvent> chatForEvaluation(long knowledgeBaseId,
                                                    String requestedSessionId,
                                                    String message) {
        return chat(knowledgeBaseId, requestedSessionId, message, AgentSessionType.EVALUATION);
    }

    private Flux<AgentStreamEvent> chat(long knowledgeBaseId,
                                        String requestedSessionId,
                                        String message,
                                        AgentSessionType sessionType) {
        String normalizedMessage = requireMessage(message);
        validateKnowledgeBase(knowledgeBaseId);
        // 每次对话生成一个runId。
        String runId = UUID.randomUUID().toString();

        return Flux.defer(() -> {
            Timer.Sample runSample = metrics.start();
            String sessionId;
            try {
                sessionId = sessionType == AgentSessionType.NORMAL
                        // 抢占会话（数据库锁实现）
                        ? sessionConcurrencyService.acquire(
                                knowledgeBaseId, requestedSessionId, runId,
                                properties.sessionConcurrency().leaseDuration())
                        : sessionConcurrencyService.acquire(
                                knowledgeBaseId, requestedSessionId, runId,
                                properties.sessionConcurrency().leaseDuration(), sessionType);
            } catch (AgentSessionBusyException busy) {
                metrics.agentCompleted(runSample, "session_busy");
                AgentExecutionException failure = AgentExecutionException.sessionBusy();
                return Flux.just(AgentStreamEvent.error(
                        runId, busy.sessionId(), failure.code(), failure.clientMessage()));
            }
            List<Message> history;
            try {
                // 计算agent token预算
                int memoryBudget = tokenBudgetCalculator.agentMemoryBudget(
                        promptFactory.create(List.of(), normalizedMessage),
                        properties.memory().maxTokens());
                // 加载对话历史
                history = memoryService.loadRecentMessages(
                        sessionId, properties.memory().maxMessages(), memoryBudget);
            } catch (ContextBudgetExceededException exceeded) {
                sessionConcurrencyService.release(sessionId, runId);
                metrics.contextBudgetExceeded("agent");
                metrics.agentCompleted(runSample, "context_budget_exceeded");
                AgentExecutionException failure = AgentExecutionException.contextBudget(exceeded);
                return Flux.just(AgentStreamEvent.error(
                        runId, sessionId, failure.code(), failure.clientMessage()));
            } catch (RuntimeException failure) {
                sessionConcurrencyService.release(sessionId, runId);
                throw failure;
            }
            // unicast: 每个订阅者都会创建一个独立的流，不会相互影响  onBackpressureBuffer:下游消费不过来时先排队
            Sinks.Many<AgentStreamEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
            Object emissionLock = new Object();
            AgentToolEventPublisher publisher = event -> emit(eventSink, emissionLock, event);
            AgentRunContext runContext = new AgentRunContext(
                    runId, sessionId, knowledgeBaseId, history.size(),
                    properties.maxDuration(), clock, publisher,
                    properties.toolResult().maxTokensPerRun());
            ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                    .model(chatProperties.model())
                    .temperature(chatProperties.temperature())
                    .toolCallbacks(toolCatalog.callbacks())
                    .toolContext(Map.of(
                            KnowledgeSearchTool.CONTEXT_KNOWLEDGE_BASE_ID, knowledgeBaseId,
                            KnowledgeSearchTool.CONTEXT_AGENT_RUN, runContext));
            if (tokenBudgetCalculator.properties().reservedOutputTokens() > 0) {
                optionsBuilder.maxTokens(tokenBudgetCalculator.properties().reservedOutputTokens());
            }
            ToolCallingChatOptions options = optionsBuilder.build();
            Prompt prompt = new Prompt(promptFactory.create(history, normalizedMessage), options);
            // 执行
            Mono<Void> execution = runLoop(runContext, prompt, normalizedMessage, runSample)
                    .onErrorResume(error -> finishWithError(runContext, error, runSample))
                    .doFinally(signal -> {
                        sessionConcurrencyService.release(sessionId, runId);
                        complete(eventSink, emissionLock);
                    });

            return Flux.merge(eventSink.asFlux(), execution.thenMany(Flux.empty()))
                    .doOnCancel(() -> log.info(
                            "Agent stream cancelled: runId={}, sessionId={}, knowledgeBaseId={}, modelTurns={}, "
                                    + "toolCalls={}, durationMs={}",
                            runContext.runId(), runContext.sessionId(), knowledgeBaseId,
                            runContext.modelTurnCount(),
                            runContext.toolCallCount(), runContext.elapsedMillis()));
        });
    }

    private Mono<Void> runLoop(AgentRunContext runContext,
                               Prompt prompt,
                               String currentUserMessage,
                               Timer.Sample runSample) {
        return Mono.defer(() -> {
            runContext.ensureTimeRemaining();
            try {
                tokenBudgetCalculator.validateAgentMessages(prompt.getInstructions());
            } catch (ContextBudgetExceededException exceeded) {
                throw AgentExecutionException.contextBudget(exceeded);
            }
            int turn = runContext.incrementModelTurn();
            metrics.agentModelTurn();
            long turnStarted = System.nanoTime();
            return streamModelTurn(runContext, prompt)
                    .flatMap(response -> {
                        runContext.ensureTimeRemaining();
                        List<AssistantMessage.ToolCall> toolCalls = toolCalls(response);
                        log.info("Agent model turn completed: runId={}, sessionId={}, historyMessageCount={}, "
                                        + "model={}, turn={}, hasToolCalls={}, requestedToolNames={}, durationMs={}",
                                runContext.runId(), runContext.sessionId(),
                                runContext.historyMessageCount(), chatProperties.model(), turn,
                                !toolCalls.isEmpty(),
                                toolCalls.stream().map(AssistantMessage.ToolCall::name).toList(),
                                elapsedMillis(turnStarted));

                        // 如果没有工具调用，则完成当前轮次，否则执行工具调用
                        if (toolCalls.isEmpty()) {
                            String finalAssistantContent = requireFinalAssistantContent(response);
                            return persistSuccessfulTurn(
                                    runContext, currentUserMessage, finalAssistantContent, runSample);
                        }
                        // 检查工具调用数量
                        runContext.reserveToolCalls(toolCalls.size(), properties.maxToolCalls());
                        runContext.ensureTimeRemaining();
                        return executeToolCalls(runContext, prompt, response)
                                .flatMap(result -> {
                                    runContext.ensureTimeRemaining();
                                    Prompt nextPrompt = new Prompt(
                                            // 拼接当前轮次的会话历史和工具调用结果
                                            result.conversationHistory(), prompt.getOptions());
                                    return runLoop(runContext, nextPrompt, currentUserMessage, runSample);
                                });
                    });
        });
    }

    private Mono<Void> persistSuccessfulTurn(AgentRunContext runContext,
                                             String userContent,
                                             String assistantContent,
                                             Timer.Sample runSample) {
        Set<String> invalidCitations = citationValidator.invalidCitations(
                assistantContent, runContext.sourceRegistry());
        if (!invalidCitations.isEmpty()) {
            metrics.invalidCitations(invalidCitations.size());
            log.warn("Agent answer contains invalid run-scoped citations: runId={}, invalidCitationCount={}",
                    runContext.runId(), invalidCitations.size());
        }
        Duration remaining = runContext.remaining();
        return Mono.fromRunnable(() -> memoryService.appendSuccessfulTurn(
                        runContext.sessionId(), runContext.runId(), userContent, assistantContent)) // 写入会话历史
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(remaining)
                .onErrorMap(TimeoutException.class, ignored -> AgentExecutionException.timeout())
                .onErrorMap(AgentSessionLeaseLostException.class,
                        AgentExecutionException::leaseLost)
                .then(Mono.fromRunnable(() -> {
                    metrics.agentCompleted(runSample, "success");
                    log.info("Agent run completed: runId={}, sessionId={}, modelTurns={}, toolCalls={}, "
                                    + "durationMs={}, outcome=success",
                            runContext.runId(), runContext.sessionId(), runContext.modelTurnCount(),
                            runContext.toolCallCount(), runContext.elapsedMillis());
                    runContext.publish(AgentStreamEvent.done(
                            runContext.runId(), runContext.sessionId(), runContext.toolCallCount(),
                            runContext.modelTurnCount(), runContext.elapsedMillis(),
                            runContext.sourceRegistry().snapshot()));
                }));
    }

    private Mono<ChatResponse> streamModelTurn(AgentRunContext runContext, Prompt prompt) {
        AtomicReference<ChatClientResponse> aggregated = new AtomicReference<>();
        AtomicBoolean assistantDeltaPublished = new AtomicBoolean();
        Duration remaining = runContext.remaining();
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(
                        streamingRetry.execute(
                                        "openai-compatible-chat", "agent-model-turn",
                                        () -> modelTurnStreamer.stream(prompt),
                                        () -> assistantDeltaPublished.get()
                                                || runContext.toolCallCount() > 0,
                                        runContext::remaining,
                                        AgentExecutionException::timeout)
                                .timeout(remaining),
                        aggregated::set)
                .doOnNext(chunk -> {
                    if (hasVisibleAssistantContent(chunk)) {
                        assistantDeltaPublished.set(true);
                    }
                    publishAssistantDelta(runContext, chunk);
                })
                .then(Mono.fromSupplier(() -> requireChatResponse(aggregated.get())))
                .onErrorMap(AgentChatService::mapModelError);
    }

    private static boolean hasVisibleAssistantContent(ChatClientResponse chunk) {
        if (chunk == null || chunk.chatResponse() == null
                || chunk.chatResponse().getResult() == null
                || chunk.chatResponse().getResult().getOutput() == null) {
            return false;
        }
        String content = chunk.chatResponse().getResult().getOutput().getText();
        return content != null && !content.isEmpty();
    }

    private Mono<ToolExecutionResult> executeToolCalls(AgentRunContext runContext,
                                                        Prompt prompt,
                                                        ChatResponse response) {
        Duration remaining = runContext.remaining();
        return Mono.fromCallable(() -> toolCallingManager.executeToolCalls(prompt, response))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(remaining)
                .onErrorMap(this::mapToolError);
    }

    private Mono<Void> finishWithError(AgentRunContext runContext,
                                       Throwable error,
                                       Timer.Sample runSample) {
        AgentExecutionException failure = toAgentFailure(error);
        metrics.agentCompleted(runSample, failure.code().toLowerCase(java.util.Locale.ROOT));
        if ("AGENT_CONTEXT_BUDGET_EXCEEDED".equals(failure.code())) {
            metrics.contextBudgetExceeded("agent");
        }
        log.error("Agent run failed: runId={}, sessionId={}, knowledgeBaseId={}, model={}, modelTurns={}, "
                        + "toolCalls={}, durationMs={}, outcome=error, code={}, errorType={}",
                runContext.runId(), runContext.sessionId(), runContext.knowledgeBaseId(), chatProperties.model(),
                runContext.modelTurnCount(), runContext.toolCallCount(), runContext.elapsedMillis(),
                failure.code(), error.getClass().getSimpleName());
        runContext.publish(AgentStreamEvent.error(
                runContext.runId(), runContext.sessionId(), failure.code(), failure.clientMessage()));
        return Mono.empty();
    }

    private void validateKnowledgeBase(long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.get(knowledgeBaseId);
        if (knowledgeBase.getStatus() != KnowledgeBaseStatus.ACTIVE) {
            throw new KnowledgeBaseInactiveException(knowledgeBaseId);
        }
    }

    private static void publishAssistantDelta(AgentRunContext runContext,
                                              ChatClientResponse chunk) {
        ChatResponse response = chunk.chatResponse();
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return;
        }
        String content = response.getResult().getOutput().getText();
        if (content != null && !content.isEmpty()) {
            runContext.publish(AgentStreamEvent.assistantDelta(
                    runContext.runId(), runContext.sessionId(), content));
        }
    }

    private static ChatResponse requireChatResponse(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            throw new IllegalStateException("Chat model returned no aggregate response");
        }
        return response.chatResponse();
    }

    private static String requireFinalAssistantContent(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Final model turn returned no assistant message");
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Final model turn returned empty assistant content");
        }
        return content;
    }

    private static List<AssistantMessage.ToolCall> toolCalls(ChatResponse response) {
        if (response == null || !response.hasToolCalls()) {
            return List.of();
        }
        return response.getResults().stream()
                .map(result -> result.getOutput())
                .filter(AssistantMessage::hasToolCalls)
                .findFirst()
                .map(AssistantMessage::getToolCalls)
                .orElseGet(List::of);
    }

    private static Throwable mapModelError(Throwable error) {
        AgentExecutionException existing = findAgentFailure(error);
        if (existing != null) {
            return existing;
        }
        if (error instanceof TimeoutException) {
            return AgentExecutionException.timeout();
        }
        if (hasCause(error, AgentSessionLeaseLostException.class)) {
            return AgentExecutionException.leaseLost(error);
        }
        if (hasCause(error, ContextBudgetExceededException.class)) {
            return AgentExecutionException.contextBudget(error);
        }
        return AgentExecutionException.model(error);
    }

    private Throwable mapToolError(Throwable error) {
        AgentExecutionException existing = findAgentFailure(error);
        if (existing != null) {
            return existing;
        }
        if (error instanceof TimeoutException) {
            return AgentExecutionException.timeout();
        }
        if (hasCause(error, ContextBudgetExceededException.class)) {
            return AgentExecutionException.contextBudget(error);
        }
        if (hasCause(error, McpToolExecutionException.class)) {
            return AgentExecutionException.mcpTool(error);
        }
        if (error instanceof ToolExecutionException || hasCause(error, ToolExecutionException.class)) {
            return AgentExecutionException.tool(error);
        }
        return AgentExecutionException.tool(error);
    }

    private static AgentExecutionException toAgentFailure(Throwable error) {
        AgentExecutionException existing = findAgentFailure(error);
        return existing == null ? AgentExecutionException.internal(error) : existing;
    }

    private static AgentExecutionException findAgentFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AgentExecutionException agentFailure) {
                return agentFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent message is required");
        }
        String normalized = message.trim();
        if (normalized.length() > 4000) {
            throw new IllegalArgumentException("Agent message exceeds 4000 characters");
        }
        return normalized;
    }

    private static void emit(Sinks.Many<AgentStreamEvent> sink,
                             Object lock,
                             AgentStreamEvent event) {
        // 加锁 多个线程可能会同时 emit
        synchronized (lock) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure() && result != Sinks.EmitResult.FAIL_CANCELLED
                    && result != Sinks.EmitResult.FAIL_TERMINATED) {
                throw new IllegalStateException("Could not emit agent event: " + result);
            }
        }
    }

    private static void complete(Sinks.Many<AgentStreamEvent> sink, Object lock) {
        synchronized (lock) {
            sink.tryEmitComplete();
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
