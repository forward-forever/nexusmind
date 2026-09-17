package com.wude.nexusmind.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nexusmind.ai.resilience")
public record AiResilienceProperties(
        int maxRetries,
        Duration initialDelay,
        double multiplier,
        Duration maxDelay,
        Duration jitter) {

    public AiResilienceProperties {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Provider max retries must not be negative");
        }
        requireNonNegative(initialDelay, "Provider retry initial delay");
        requireNonNegative(maxDelay, "Provider retry max delay");
        requireNonNegative(jitter, "Provider retry jitter");
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("Provider retry multiplier must be at least 1.0");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Provider retry max delay must cover initial delay");
        }
    }

    public Duration delayForRetry(long retryNumber) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("Retry number must be positive");
        }
        double factor = Math.pow(multiplier, retryNumber - 1);
        double candidate = initialDelay.toNanos() * factor;
        long computed = candidate >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) candidate;
        return Duration.ofNanos(Math.min(computed, maxDelay.toNanos()));
    }

    private static void requireNonNegative(Duration duration, String name) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
