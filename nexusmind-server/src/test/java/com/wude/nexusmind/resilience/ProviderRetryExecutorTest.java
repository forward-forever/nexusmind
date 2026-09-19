package com.wude.nexusmind.resilience;

import com.wude.nexusmind.observability.NexusMindMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProviderRetryExecutorTest {

    @Test
    void retries503And429ThenReturnsSuccess() {
        assertRetryThenSuccess(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "unavailable", HttpHeaders.EMPTY, null, null));
        assertRetryThenSuccess(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "limited", HttpHeaders.EMPTY, null, null));
    }

    @Test
    void doesNotRetry401Or400() {
        assertNoRetry(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "unauthorized", HttpHeaders.EMPTY, null, null));
        assertNoRetry(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad request", HttpHeaders.EMPTY, null, null));
    }

    @Test
    void retriesNetworkTimeout() {
        AtomicInteger attempts = new AtomicInteger();

        String result = executor(2).execute("test", "network", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException(new SocketTimeoutException("timed out"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void stopsAfterConfiguredRetryCountAndPropagatesProviderFailure() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException failure = HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "bad gateway", HttpHeaders.EMPTY, null, null);

        assertThatThrownBy(() -> executor(2).execute("test", "exhausted", () -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void metricsCountActualProviderAttemptsAndRetryDecision() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProviderRetryExecutor executor = new ProviderRetryExecutor(
                new AiResilienceProperties(
                        2, Duration.ZERO, 1.0, Duration.ofNanos(1), Duration.ZERO),
                new ProviderFailureClassifier(), new NexusMindMetrics(registry));
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute("alibaba", "embedding-batch", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", HttpHeaders.EMPTY, null, null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(registry.get("nexusmind.ai.provider.calls")
                .tag("operation", "embedding").counters().stream()
                .mapToDouble(counter -> counter.count()).sum()).isEqualTo(2);
        assertThat(registry.get("nexusmind.ai.provider.errors")
                .tag("operation", "embedding").counter().count()).isEqualTo(1);
        assertThat(registry.get("nexusmind.ai.provider.retries")
                .tag("failureCategory", "http_5xx").counter().count()).isEqualTo(1);
    }

    private static void assertRetryThenSuccess(RuntimeException failure) {
        AtomicInteger attempts = new AtomicInteger();
        String result = executor(2).execute("test", "http", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw failure;
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    private static void assertNoRetry(RuntimeException failure) {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> executor(2).execute("test", "http", () -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);
        assertThat(attempts).hasValue(1);
    }

    public static ProviderRetryExecutor executor(int maxRetries) {
        return new ProviderRetryExecutor(
                new AiResilienceProperties(
                        maxRetries, Duration.ZERO, 1.0, Duration.ofNanos(1), Duration.ZERO),
                new ProviderFailureClassifier());
    }
}
