package com.wude.nexusmind.resilience;

import com.wude.nexusmind.observability.NexusMindMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;

import java.util.function.Supplier;
import java.time.Duration;

public final class ProviderRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProviderRetryExecutor.class);

    private final AiResilienceProperties properties;
    private final ProviderFailureClassifier classifier;
    private final RetryPolicy policy;
    private final NexusMindMetrics metrics;

    public ProviderRetryExecutor(AiResilienceProperties properties,
                                 ProviderFailureClassifier classifier) {
        this(properties, classifier, NexusMindMetrics.noop());
    }

    public ProviderRetryExecutor(AiResilienceProperties properties,
                                 ProviderFailureClassifier classifier,
                                 NexusMindMetrics metrics) {
        this.properties = properties;
        this.classifier = classifier;
        this.metrics = metrics;
        this.policy = RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(properties.initialDelay())
                .multiplier(properties.multiplier())
                .maxDelay(properties.maxDelay())
                .jitter(properties.jitter())
                .predicate(error -> classifier.classify(error) == ProviderFailureCategory.RETRYABLE)
                .build();
    }

    public static ProviderRetryExecutor noRetry() {
        return new ProviderRetryExecutor(
                new AiResilienceProperties(0, Duration.ZERO, 1.0, Duration.ofNanos(1), Duration.ZERO),
                new ProviderFailureClassifier());
    }

    public <T> T execute(String provider, String operation, Supplier<T> action) {
        RetryTemplate template = new RetryTemplate(policy);
        template.setRetryListener(new LoggingRetryListener(provider, operation));
        return template.invoke(() -> measuredAttempt(provider, operation, action));
    }

    private <T> T measuredAttempt(String provider, String operation, Supplier<T> action) {
        Timer.Sample sample = metrics.start();
        try {
            T result = action.get();
            metrics.providerCompleted(sample, provider, operation, "success");
            return result;
        } catch (RuntimeException failure) {
            metrics.providerCompleted(sample, provider, operation, "error");
            throw failure;
        }
    }

    public AiResilienceProperties properties() {
        return properties;
    }

    public ProviderFailureClassifier classifier() {
        return classifier;
    }

    private final class LoggingRetryListener implements RetryListener {

        private final String provider;
        private final String operation;

        private LoggingRetryListener(String provider, String operation) {
            this.provider = provider;
            this.operation = operation;
        }

        @Override
        public void beforeRetry(RetryPolicy retryPolicy,
                                Retryable<?> retryable,
                                RetryState state) {
            Throwable failure = state.getLastException();
            long retryNumber = Math.max(1, state.getRetryCount());
            String status = classifier.httpStatus(failure).isPresent()
                    ? Integer.toString(classifier.httpStatus(failure).getAsInt()) : "n/a";
            log.warn("Provider call retrying: provider={}, operation={}, attempt={}, maxAttempts={}, "
                            + "failureCategory={}, httpStatus={}, nextDelayMs={}",
                    provider, operation, retryNumber + 1, properties.maxRetries() + 1,
                    classifier.classify(failure), status,
                    properties.delayForRetry(retryNumber).toMillis());
            metrics.providerRetry(provider, operation, classifier.metricCategory(failure));
        }
    }
}
