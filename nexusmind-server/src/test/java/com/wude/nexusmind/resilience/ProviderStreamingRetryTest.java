package com.wude.nexusmind.resilience;

import com.wude.nexusmind.agent.application.AgentExecutionException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderStreamingRetryTest {

    @Test
    void retriesFailureBeforeFirstObservableDelta() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean visible = new AtomicBoolean();
        ProviderStreamingRetry retry = retry(2, Duration.ZERO);

        Flux<String> result = retry.execute("chat", "turn", () -> {
                    if (attempts.incrementAndGet() == 1) {
                        return Flux.error(timeout());
                    }
                    return Flux.just("MVCC").doOnNext(ignored -> visible.set(true));
                }, visible::get, () -> Duration.ofSeconds(1), AgentExecutionException::timeout);

        assertThat(result.collectList().block()).containsExactly("MVCC");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void neverRetriesAfterFirstObservableDelta() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean visible = new AtomicBoolean();
        ProviderStreamingRetry retry = retry(2, Duration.ZERO);

        Flux<String> result = retry.execute("chat", "turn", () -> {
                    attempts.incrementAndGet();
                    return Flux.concat(
                            Flux.just("MVCC").doOnNext(ignored -> visible.set(true)),
                            Flux.error(timeout()));
                }, visible::get, () -> Duration.ofSeconds(1), AgentExecutionException::timeout);

        assertThatThrownBy(() -> result.collectList().block())
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void refusesRetryWhenBackoffWouldExceedAgentDeadline() {
        AtomicInteger attempts = new AtomicInteger();
        ProviderStreamingRetry retry = retry(2, Duration.ofMillis(10));

        Flux<String> result = retry.execute("chat", "agent-turn", () -> {
                    attempts.incrementAndGet();
                    return Flux.error(timeout());
                }, () -> false, () -> Duration.ofMillis(5), AgentExecutionException::timeout);

        assertThatThrownBy(result::blockLast)
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).code())
                .isEqualTo("AGENT_TIMEOUT");
        assertThat(attempts).hasValue(1);
    }

    private static ProviderStreamingRetry retry(int maxRetries, Duration delay) {
        Duration maxDelay = delay.isZero() ? Duration.ofNanos(1) : delay;
        return new ProviderStreamingRetry(
                new AiResilienceProperties(maxRetries, delay, 1.0, maxDelay, Duration.ZERO),
                new ProviderFailureClassifier());
    }

    private static IllegalStateException timeout() {
        return new IllegalStateException(new SocketTimeoutException("provider timeout"));
    }
}
