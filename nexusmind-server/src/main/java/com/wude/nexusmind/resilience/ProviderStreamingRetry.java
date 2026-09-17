package com.wude.nexusmind.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class ProviderStreamingRetry {

    private static final Logger log = LoggerFactory.getLogger(ProviderStreamingRetry.class);

    private final AiResilienceProperties properties;
    private final ProviderFailureClassifier classifier;

    public ProviderStreamingRetry(AiResilienceProperties properties,
                                  ProviderFailureClassifier classifier) {
        this.properties = properties;
        this.classifier = classifier;
    }

    public static ProviderStreamingRetry noRetry() {
        return new ProviderStreamingRetry(
                new AiResilienceProperties(0, Duration.ZERO, 1.0, Duration.ofNanos(1), Duration.ZERO),
                new ProviderFailureClassifier());
    }

    public <T> Flux<T> execute(String provider,
                               String operation,
                               Supplier<Flux<T>> streamFactory,
                               BooleanSupplier observableSideEffect,
                               Supplier<Duration> remaining,
                               Supplier<? extends RuntimeException> deadlineFailure) {
        return Flux.defer(streamFactory)
                .retryWhen(Retry.from(signals -> signals.concatMap(signal -> {
                    Throwable failure = signal.failure();
                    long retryNumber = signal.totalRetries() + 1;
                    if (observableSideEffect.getAsBoolean()
                            || retryNumber > properties.maxRetries()
                            || classifier.classify(failure) != ProviderFailureCategory.RETRYABLE) {
                        return Mono.error(failure);
                    }
                    Duration delay = withJitter(properties.delayForRetry(retryNumber));
                    Duration timeLeft;
                    try {
                        timeLeft = remaining.get();
                    } catch (RuntimeException expired) {
                        return Mono.error(expired);
                    }
                    if (delay.compareTo(timeLeft) >= 0) {
                        return Mono.error(deadlineFailure.get());
                    }
                    String status = classifier.httpStatus(failure).isPresent()
                            ? Integer.toString(classifier.httpStatus(failure).getAsInt()) : "n/a";
                    log.warn("Streaming provider call retrying: provider={}, operation={}, attempt={}, "
                                    + "maxAttempts={}, failureCategory={}, httpStatus={}, nextDelayMs={}",
                            provider, operation, retryNumber + 1, properties.maxRetries() + 1,
                            classifier.classify(failure), status, delay.toMillis());
                    return delay.isZero() ? Mono.just(retryNumber) : Mono.delay(delay);
                })));
    }

    private Duration withJitter(Duration delay) {
        long jitterNanos = properties.jitter().toNanos();
        if (jitterNanos == 0) {
            return delay;
        }
        long addition = ThreadLocalRandom.current().nextLong(jitterNanos + 1);
        Duration jittered = delay.plusNanos(addition);
        return jittered.compareTo(properties.maxDelay()) > 0
                ? properties.maxDelay() : jittered;
    }
}
