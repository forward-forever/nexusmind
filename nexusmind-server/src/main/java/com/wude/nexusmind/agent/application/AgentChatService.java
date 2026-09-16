package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.memory.AgentConversationMemoryService;
import com.wude.nexusmind.agent.prompt.AgentPromptFactory;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.stream.AgentToolEventPublisher;
import com.wude.nexusmind.agent.tool.AgentToolSet;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentModelTurnStreamer modelTurnStreamer;
    private final ToolCallingManager toolCallingManager;
    private final AgentToolSet toolSet;
    private final AgentPromptFactory promptFactory;
    private final AgentConversationMemoryService memoryService;
    private final AgentProperties properties;
    private final RagChatProperties chatProperties;
    private final Clock clock;

    public AgentChatService(KnowledgeBaseService knowledgeBaseService,
                            AgentModelTurnStreamer modelTurnStreamer,
                            @Qualifier("agentToolCallingManager") ToolCallingManager toolCallingManager,
                            AgentToolSet toolSet,
                            AgentPromptFactory promptFactory,
                            AgentConversationMemoryService memoryService,
                            AgentProperties properties,
                            RagChatProperties chatProperties,
                            Clock clock) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelTurnStreamer = modelTurnStreamer;
        this.toolCallingManager = toolCallingManager;
        this.toolSet = toolSet;
        this.promptFactory = promptFactory;
        this.memoryService = memoryService;
        this.properties = properties;
        this.chatProperties = chatProperties;
        this.clock = clock;
    }

    public Flux<AgentStreamEvent> chat(long knowledgeBaseId, String message) {
        return chat(knowledgeBaseId, null, message);
    }

    public Flux<AgentStreamEvent> chat(long knowledgeBaseId,
                                       String requestedSessionId,
                                       String message) {
        String normalizedMessage = requireMessage(message);
        validateKnowledgeBase(knowledgeBaseId);
        String sessionId = memoryService.resolveSession(knowledgeBaseId, requestedSessionId);
        List<Message> history = memoryService.loadRecentMessages(
                sessionId, properties.memory().maxMessages());

        return Flux.defer(() -> {
            Sinks.Many<AgentStreamEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
            Object emissionLock = new Object();
            AgentToolEventPublisher publisher = event -> emit(eventSink, emissionLock, event);
            AgentRunContext runContext = new AgentRunContext(
                    sessionId, knowledgeBaseId, history.size(),
                    properties.maxDuration(), clock, publisher);
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .model(chatProperties.model())
                    .temperature(chatProperties.temperature())
                    .toolCallbacks(toolSet.callbacks())
                    .toolContext(Map.of(
                            KnowledgeSearchTool.CONTEXT_KNOWLEDGE_BASE_ID, knowledgeBaseId,
                            KnowledgeSearchTool.CONTEXT_AGENT_RUN, runContext))
                    .build();
            Prompt prompt = new Prompt(promptFactory.create(history, normalizedMessage), options);

            Mono<Void> execution = runLoop(runContext, prompt, normalizedMessage)
                    .onErrorResume(error -> finishWithError(runContext, error))
                    .doFinally(signal -> complete(eventSink, emissionLock));

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
                               String currentUserMessage) {
        return Mono.defer(() -> {
            runContext.ensureTimeRemaining();
            int turn = runContext.incrementModelTurn();
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
                                    runContext, currentUserMessage, finalAssistantContent);
                        }

                        runContext.reserveToolCalls(toolCalls.size(), properties.maxToolCalls());
                        runContext.ensureTimeRemaining();
                        return executeToolCalls(runContext, prompt, response)
                                .flatMap(result -> {
                                    runContext.ensureTimeRemaining();
                                    Prompt nextPrompt = new Prompt(
                                            result.conversationHistory(), prompt.getOptions());
                                    return runLoop(runContext, nextPrompt, currentUserMessage);
                                });
                    });
        });
    }

    private Mono<Void> persistSuccessfulTurn(AgentRunContext runContext,
                                             String userContent,
                                             String assistantContent) {
        Duration remaining = runContext.remaining();
        return Mono.fromRunnable(() -> memoryService.appendSuccessfulTurn(
                        runContext.sessionId(), userContent, assistantContent))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(remaining)
                .onErrorMap(TimeoutException.class, ignored -> AgentExecutionException.timeout())
                .then(Mono.fromRunnable(() -> runContext.publish(AgentStreamEvent.done(
                        runContext.runId(), runContext.sessionId(), runContext.toolCallCount(),
                        runContext.modelTurnCount(), runContext.elapsedMillis(),
                        runContext.sourceRegistry().snapshot()))));
    }

    private Mono<ChatResponse> streamModelTurn(AgentRunContext runContext, Prompt prompt) {
        AtomicReference<ChatClientResponse> aggregated = new AtomicReference<>();
        Duration remaining = runContext.remaining();
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(
                        modelTurnStreamer.stream(prompt).timeout(remaining),
                        aggregated::set)
                .doOnNext(chunk -> publishAssistantDelta(runContext, chunk))
                .then(Mono.fromSupplier(() -> requireChatResponse(aggregated.get())))
                .onErrorMap(error -> mapModelError(error));
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

    private Mono<Void> finishWithError(AgentRunContext runContext, Throwable error) {
        AgentExecutionException failure = toAgentFailure(error);
        log.error("Agent run failed: runId={}, sessionId={}, knowledgeBaseId={}, model={}, modelTurns={}, "
                        + "toolCalls={}, durationMs={}, code={}, errorType={}",
                runContext.runId(), runContext.sessionId(), runContext.knowledgeBaseId(), chatProperties.model(),
                runContext.modelTurnCount(), runContext.toolCallCount(), runContext.elapsedMillis(),
                failure.code(), error.getClass().getSimpleName(), error);
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
