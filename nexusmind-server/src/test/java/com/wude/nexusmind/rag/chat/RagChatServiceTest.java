package com.wude.nexusmind.rag.chat;

import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.api.RagStreamEvent;
import com.wude.nexusmind.rag.context.RagContext;
import com.wude.nexusmind.rag.context.RagContextBuilder;
import com.wude.nexusmind.rag.context.RagSource;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import com.wude.nexusmind.rag.retrieval.DenseRetrievalService;
import com.wude.nexusmind.rag.retrieval.DenseSearchResult;
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
        verify(fixture.retrieval).search(7L, "explain MVCC", 5);
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
        DenseRetrievalService retrieval = mock(DenseRetrievalService.class);
        when(retrieval.search(7L, "question", 5)).thenThrow(new IllegalStateException("milvus unavailable"));
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
        DenseRetrievalService retrieval = mock(DenseRetrievalService.class);
        RagContextBuilder contextBuilder = mock(RagContextBuilder.class);
        ChatAnswerStreamer streamer = mock(ChatAnswerStreamer.class);
        when(retrieval.search(7L, "unknown", 5)).thenReturn(
                new DenseSearchResult("unknown", 7L, "embedding", 4, "COSINE", 5, List.of()));
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
        DenseRetrievalService retrieval = mock(DenseRetrievalService.class);
        RagContextBuilder contextBuilder = mock(RagContextBuilder.class);
        ChatAnswerStreamer streamer = mock(ChatAnswerStreamer.class);
        DenseVectorHit hit = new DenseVectorHit(101L, 10L, 0, 0.9f, "content", 17, null);
        RagSource source = new RagSource("S1", 101L, 10L, "mysql.pdf", 17, null, 0.9f, "content");
        String contextText = "===== SOURCE S1 =====\ncontent\n===== END SOURCE S1 =====\n";
        RagContext context = new RagContext(contextText, List.of(source), contextText.length());
        when(retrieval.search(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new DenseSearchResult("question", 7L, "embedding", 4, "COSINE", 5, List.of(hit)));
        when(contextBuilder.build(List.of(hit))).thenReturn(context);
        when(streamer.stream(org.mockito.ArgumentMatchers.any())).thenReturn(modelFlux);
        return new Fixture(service(retrieval, contextBuilder, streamer, streamTimeout), retrieval);
    }

    private static RagChatService service(DenseRetrievalService retrieval,
                                          RagContextBuilder contextBuilder,
                                          ChatAnswerStreamer streamer) {
        return new RagChatService(
                retrieval,
                contextBuilder,
                new RagPromptFactory(),
                streamer,
                properties(Duration.ofMinutes(2)));
    }

    private static RagChatService service(DenseRetrievalService retrieval,
                                          RagContextBuilder contextBuilder,
                                          ChatAnswerStreamer streamer,
                                          Duration streamTimeout) {
        return new RagChatService(
                retrieval,
                contextBuilder,
                new RagPromptFactory(),
                streamer,
                properties(streamTimeout));
    }

    private static RagChatProperties properties(Duration streamTimeout) {
        return new RagChatProperties(
                "qwen3.5-flash", 0.2, 5, 10, 12_000, streamTimeout);
    }

    private record Fixture(RagChatService service, DenseRetrievalService retrieval) {
    }
}
