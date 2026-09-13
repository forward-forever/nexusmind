package com.wude.nexusmind.rag.chat;

import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.api.RagStreamEvent;
import com.wude.nexusmind.rag.context.RagContext;
import com.wude.nexusmind.rag.context.RagContextBuilder;
import com.wude.nexusmind.rag.context.RagSource;
import com.wude.nexusmind.rag.retrieval.RetrievalHit;
import com.wude.nexusmind.rag.retrieval.RetrievalResult;
import com.wude.nexusmind.rag.retrieval.RetrievalScoreType;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RagRetrievalProperties;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    @Test
    void keepsDenseAsProductRetrieverWhenRegistryAlsoContainsBm25() {
        RetrievalService dense = mock(RetrievalService.class);
        RetrievalService bm25 = mock(RetrievalService.class);
        when(dense.type()).thenReturn(RetrieverType.DENSE);
        when(bm25.type()).thenReturn(RetrieverType.BM25);
        when(dense.retrieve(7L, "question", 5)).thenReturn(new RetrievalResult(
                "question", 7L, "embedding", 4, RetrieverType.DENSE,
                RetrievalScoreType.COSINE, 5, List.of()));
        RagContextBuilder contextBuilder = mock(RagContextBuilder.class);
        when(contextBuilder.build(List.of())).thenReturn(new RagContext("", List.of(), 0));
        ChatAnswerStreamer streamer = mock(ChatAnswerStreamer.class);
        RagChatService service = new RagChatService(
                new RetrievalServiceRegistry(List.of(dense, bm25)),
                new RagRetrievalProperties(RetrieverType.DENSE),
                contextBuilder,
                new RagPromptFactory(),
                streamer,
                properties(Duration.ofMinutes(2)));

        service.stream(7L, "question", null).collectList().block();

        verify(dense).retrieve(7L, "question", 5);
        verify(bm25, never()).retrieve(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void streamsSourcesThenRealModelDeltasThenDone() {
        Fixture fixture = fixture(Flux.just("MV", "CC", " 是", "...[S1]"));

        List<RagStreamEvent> events = fixture.service.stream(7L, "  explain MVCC  ", null)
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(RagStreamEvent::type)
                .containsExactly("sources", "delta", "delta", "delta", "delta", "done");
        assertThat(events.get(0).sources()).hasSize(1);
        assertThat(events.get(0).sources().get(0).id()).isEqualTo("S1");
        assertThat(events.subList(1, 5)).extracting(RagStreamEvent::content)
                .containsExactly("MV", "CC", " 是", "...[S1]");
        assertThat(events.get(5).model()).isEqualTo("qwen3.5-flash");
        verify(fixture.retrieval).retrieve(7L, "explain MVCC", 5);
    }

    @Test
    void convertsModelFailureAfterSourcesAndDeltaIntoErrorWithoutDone() {
        Fixture fixture = fixture(Flux.concat(
                Flux.just("partial"),
                Flux.error(new IllegalStateException("provider failed"))));

        List<RagStreamEvent> events = fixture.service.stream(7L, "question", 3)
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(RagStreamEvent::type)
                .containsExactly("sources", "delta", "error");
        assertThat(events.get(2).code()).isEqualTo("CHAT_MODEL_ERROR");
        assertThat(events).noneMatch(event -> "done".equals(event.type()));
    }

    @Test
    void retrievalFailureIsThrownSynchronouslyBeforeAFluxIsReturned() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(7L, "question", 5)).thenThrow(new IllegalStateException("milvus unavailable"));
        RagContextBuilder contextBuilder = mock(RagContextBuilder.class);
        ChatAnswerStreamer streamer = mock(ChatAnswerStreamer.class);
        RagChatService service = service(retrieval, contextBuilder, streamer);

        assertThatThrownBy(() -> service.stream(7L, "question", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("milvus unavailable");

        verify(contextBuilder, never()).build(org.mockito.ArgumentMatchers.anyList());
        verify(streamer, never()).stream(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyRetrievalUsesAConsistentSseFlowWithoutCallingChatModel() {
        RetrievalService retrieval = mock(RetrievalService.class);
        RagContextBuilder contextBuilder = mock(RagContextBuilder.class);
        ChatAnswerStreamer streamer = mock(ChatAnswerStreamer.class);
        when(retrieval.retrieve(7L, "unknown", 5)).thenReturn(
                new RetrievalResult(
                        "unknown", 7L, "embedding", 4, RetrieverType.DENSE,
                        RetrievalScoreType.COSINE, 5, List.of()));
        when(contextBuilder.build(List.of())).thenReturn(new RagContext("", List.of(), 0));

        List<RagStreamEvent> events = service(retrieval, contextBuilder, streamer)
                .stream(7L, "unknown", null).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(RagStreamEvent::type)
                .containsExactly("sources", "delta", "done");
        assertThat(events.get(0).sources()).isEmpty();
        assertThat(events.get(1).content()).isEqualTo("当前知识库没有可用的检索结果。");
        verify(streamer, never()).stream(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancellingTheClientSubscriptionCancelsTheDownstreamModelFlux() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Fixture fixture = fixture(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));

        Disposable subscription = fixture.service.stream(7L, "question", 5).subscribe();
        subscription.dispose();

        assertThat(cancelled).isTrue();
    }

    @Test
    void convertsModelTimeoutIntoAnErrorEventWithoutDone() {
        Fixture fixture = fixture(Flux.never(), Duration.ofMillis(20));

        List<RagStreamEvent> events = fixture.service.stream(7L, "question", 5)
                .collectList().block(Duration.ofSeconds(1));

        assertThat(events).isNotNull();
        assertThat(events).extracting(RagStreamEvent::type)
                .containsExactly("sources", "error");
        assertThat(events).noneMatch(event -> "done".equals(event.type()));
    }

    private static Fixture fixture(Flux<String> modelFlux) {
        return fixture(modelFlux, Duration.ofMinutes(2));
    }

    private static Fixture fixture(Flux<String> modelFlux, Duration streamTimeout) {
        RetrievalService retrieval = mock(RetrievalService.class);
        RagContextBuilder contextBuilder = mock(RagContextBuilder.class);
        ChatAnswerStreamer streamer = mock(ChatAnswerStreamer.class);
        RetrievalHit hit = new RetrievalHit(
                101L, 10L, "mysql.pdf", 0, 0.9f, RetrievalScoreType.COSINE, "content", 17, null);
        RagSource source = new RagSource(
                "S1", 101L, 10L, "mysql.pdf", 17, null, 0.9f, RetrievalScoreType.COSINE, "content");
        String contextText = "===== SOURCE S1 =====\ncontent\n===== END SOURCE S1 =====\n";
        RagContext context = new RagContext(contextText, List.of(source), contextText.length());
        when(retrieval.retrieve(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new RetrievalResult(
                        "question", 7L, "embedding", 4, RetrieverType.DENSE,
                        RetrievalScoreType.COSINE, 5, List.of(hit)));
        when(contextBuilder.build(List.of(hit))).thenReturn(context);
        when(streamer.stream(org.mockito.ArgumentMatchers.any())).thenReturn(modelFlux);
        return new Fixture(service(retrieval, contextBuilder, streamer, streamTimeout), retrieval);
    }

    private static RagChatService service(RetrievalService retrieval,
                                          RagContextBuilder contextBuilder,
                                          ChatAnswerStreamer streamer) {
        when(retrieval.type()).thenReturn(RetrieverType.DENSE);
        return new RagChatService(
                new RetrievalServiceRegistry(List.of(retrieval)),
                new RagRetrievalProperties(RetrieverType.DENSE),
                contextBuilder,
                new RagPromptFactory(),
                streamer,
                properties(Duration.ofMinutes(2)));
    }

    private static RagChatService service(RetrievalService retrieval,
                                          RagContextBuilder contextBuilder,
                                          ChatAnswerStreamer streamer,
                                          Duration streamTimeout) {
        when(retrieval.type()).thenReturn(RetrieverType.DENSE);
        return new RagChatService(
                new RetrievalServiceRegistry(List.of(retrieval)),
                new RagRetrievalProperties(RetrieverType.DENSE),
                contextBuilder,
                new RagPromptFactory(),
                streamer,
                properties(streamTimeout));
    }

    private static RagChatProperties properties(Duration streamTimeout) {
        return new RagChatProperties(
                "qwen3.5-flash", 0.2, 5, 10, 12_000, streamTimeout,
                streamTimeout.plusSeconds(30));
    }

    private record Fixture(RagChatService service, RetrievalService retrieval) {
    }
}
