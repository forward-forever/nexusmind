package com.wude.nexusmind.rag.chat;

import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.api.RagStreamEvent;
import com.wude.nexusmind.rag.context.RagContext;
import com.wude.nexusmind.rag.context.RagContextBuilder;
import com.wude.nexusmind.rag.context.RagSource;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RagRetrievalProperties;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.resilience.ProviderStreamingRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "nexusmind.rag.enabled", havingValue = "true")
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);
    private static final String NO_RESULTS_MESSAGE = "当前知识库没有可用的检索结果。";
    private static final Pattern SOURCE_REFERENCE = Pattern.compile("\\[S(\\d+)]");

    private final RetrievalService retrievalService;
    private final RagContextBuilder contextBuilder;
    private final RagPromptFactory promptFactory;
    private final ChatAnswerStreamer chatAnswerStreamer;
    private final RagChatProperties properties;
    private final ProviderStreamingRetry streamingRetry;

    public RagChatService(RetrievalServiceRegistry retrievalServiceRegistry,
                          RagRetrievalProperties retrievalProperties,
                          RagContextBuilder contextBuilder,
                          RagPromptFactory promptFactory,
                          ChatAnswerStreamer chatAnswerStreamer,
                          RagChatProperties properties) {
        this(retrievalServiceRegistry, retrievalProperties, contextBuilder, promptFactory,
                chatAnswerStreamer, properties, ProviderStreamingRetry.noRetry());
    }

    @Autowired
    public RagChatService(RetrievalServiceRegistry retrievalServiceRegistry,
                          RagRetrievalProperties retrievalProperties,
                          RagContextBuilder contextBuilder,
                          RagPromptFactory promptFactory,
                          ChatAnswerStreamer chatAnswerStreamer,
                          RagChatProperties properties,
                          ProviderStreamingRetry streamingRetry) {
        // 默认向量检索服务
        this.retrievalService = retrievalServiceRegistry.get(retrievalProperties.retriever());
        this.contextBuilder = contextBuilder;
        this.promptFactory = promptFactory;
        this.chatAnswerStreamer = chatAnswerStreamer;
        this.properties = properties;
        this.streamingRetry = streamingRetry;
    }

    public Flux<RagStreamEvent> stream(long knowledgeBaseId, String question, Integer requestedTopK) {
        String normalizedQuestion = requireQuestion(question);
        int topK = resolveTopK(requestedTopK);
        long requestStarted = System.nanoTime();

        long retrievalStarted = System.nanoTime();
        RetrievalResult searchResult = retrievalService.retrieve(knowledgeBaseId, normalizedQuestion, topK);
        long retrievalLatencyMs = elapsedMillis(retrievalStarted);
        RagContext context = contextBuilder.build(searchResult.hits());
        logRetrieval(knowledgeBaseId, normalizedQuestion, topK, searchResult, context, retrievalLatencyMs);
        // 构建源事件
        RagStreamEvent sourcesEvent = RagStreamEvent.sources(context.sources());
        if (context.sources().isEmpty()) {
            return noResultsFlow(knowledgeBaseId, sourcesEvent, requestStarted);
        }

        RagPrompt prompt = promptFactory.create(normalizedQuestion, context);
        StringBuilder generatedAnswer = new StringBuilder();
        AtomicBoolean firstTokenSeen = new AtomicBoolean();
        Flux<RagStreamEvent> modelFlow = streamingRetry.execute(
                        "openai-compatible-chat", "rag-model-turn",
                        () -> chatAnswerStreamer.stream(prompt)
                                .doOnNext(delta -> {
                                    if (delta != null && !delta.isEmpty()) {
                                        firstTokenSeen.set(true);
                                    }
                                }),
                        firstTokenSeen::get,
                        properties::streamTimeout,
                        () -> new IllegalStateException("RAG model retry deadline exceeded"))
                .timeout(properties.streamTimeout())
                .filter(delta -> delta != null && !delta.isEmpty())
                .map(delta -> {
                    generatedAnswer.append(delta);
                    if (generatedAnswer.length() == delta.length()) {
                        log.info("RAG first token: knowledgeBaseId={}, latencyMs={}, model={}",
                                knowledgeBaseId, elapsedMillis(requestStarted), properties.model());
                    }
                    return RagStreamEvent.delta(delta);
                })
                .concatWith(Mono.fromSupplier(() -> {
                    warnForUnknownCitations(knowledgeBaseId, generatedAnswer, context.sources());
                    // 构建完成事件
                    return RagStreamEvent.done(properties.model(), elapsedMillis(requestStarted));
                }))
                .onErrorResume(error -> {
                    log.error("RAG chat stream failed: knowledgeBaseId={}, questionChars={}, sourceCount={}, "
                                    + "model={}, latencyMs={}, errorType={}",
                            knowledgeBaseId,
                            normalizedQuestion.length(),
                            context.sources().size(),
                            properties.model(),
                            elapsedMillis(requestStarted),
                            error.getClass().getSimpleName(),
                            error);
                    return Flux.just(RagStreamEvent.error(
                            "CHAT_MODEL_ERROR", "模型生成失败，请稍后重试"));
                })
                .doOnCancel(() -> log.info(
                        "RAG stream cancelled by client: knowledgeBaseId={}, model={}, elapsedMs={}",
                        knowledgeBaseId, properties.model(), elapsedMillis(requestStarted)))
                .doOnComplete(() -> log.info(
                        "RAG stream closed: knowledgeBaseId={}, sourceCount={}, model={}, elapsedMs={}",
                        knowledgeBaseId, context.sources().size(), properties.model(), elapsedMillis(requestStarted)));

        return Flux.concat(Flux.just(sourcesEvent), modelFlow);
    }

    private Flux<RagStreamEvent> noResultsFlow(long knowledgeBaseId,
                                                RagStreamEvent sourcesEvent,
                                                long requestStarted) {
        return Flux.just(
                        sourcesEvent,
                        RagStreamEvent.delta(NO_RESULTS_MESSAGE),
                        RagStreamEvent.done(properties.model(), elapsedMillis(requestStarted)))
                .doOnCancel(() -> log.info(
                        "Empty RAG stream cancelled by client: knowledgeBaseId={}, elapsedMs={}",
                        knowledgeBaseId, elapsedMillis(requestStarted)));
    }

    private int resolveTopK(Integer requestedTopK) {
        int topK = requestedTopK == null ? properties.defaultTopK() : requestedTopK;
        if (topK < 1 || topK > properties.maxTopK()) {
            throw new IllegalArgumentException(
                    "RAG topK must be between 1 and " + properties.maxTopK());
        }
        return topK;
    }

    private static String requireQuestion(String question) {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("RAG question is required");
        }
        String normalized = question.trim();
        if (normalized.length() > 4000) {
            throw new IllegalArgumentException("RAG question exceeds 4000 characters");
        }
        return normalized;
    }

    private static void logRetrieval(long knowledgeBaseId,
                                     String question,
                                     int topK,
                                     RetrievalResult result,
                                     RagContext context,
                                     long retrievalLatencyMs) {
        String retrieved = result.hits().stream()
                .map(hit -> hit.chunkId() + ":" + hit.score())
                .toList()
                .toString();
        log.info("RAG retrieval completed: knowledgeBaseId={}, questionChars={}, topK={}, "
                        + "retrieved={}, contextSources={}, contextChars={}, retrievalLatencyMs={}",
                knowledgeBaseId,
                question.length(),
                topK,
                retrieved,
                context.sources().size(),
                context.charCount(),
                retrievalLatencyMs);
    }

    private static void warnForUnknownCitations(long knowledgeBaseId,
                                                StringBuilder answer,
                                                java.util.List<RagSource> sources) {
        Set<Integer> unknownIds = new HashSet<>();
        Matcher matcher = SOURCE_REFERENCE.matcher(answer);
        while (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number < 1 || number > sources.size()) {
                    unknownIds.add(number);
                }
            } catch (NumberFormatException ignored) {
                log.warn("RAG answer contains an invalid citation ID: knowledgeBaseId={}", knowledgeBaseId);
            }
        }
        if (!unknownIds.isEmpty()) {
            log.warn("RAG answer contains unknown citation IDs: knowledgeBaseId={}, unknownSourceNumbers={}",
                    knowledgeBaseId, unknownIds);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
