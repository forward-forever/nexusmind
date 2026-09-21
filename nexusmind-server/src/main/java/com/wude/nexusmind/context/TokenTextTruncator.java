package com.wude.nexusmind.context;

import java.util.Optional;
import java.util.function.Function;

public final class TokenTextTruncator {

    public static final String MARKER = "… [truncated]";

    private final NexusTokenEstimator estimator;

    public TokenTextTruncator(NexusTokenEstimator estimator) {
        this.estimator = estimator;
    }

    public TruncatedText truncateToEstimatedTokens(String text, int maxTokens) {
        return truncateToFitRendered(text, maxTokens, Function.identity())
                .orElseThrow(() -> new ContextBudgetExceededException(
                        "Token budget cannot fit the truncation marker"));
    }

    public Optional<TruncatedText> truncateToFitRendered(String text,
                                                          int maxTokens,
                                                          Function<String, String> renderer) {
        if (text == null) {
            text = "";
        }
        if (maxTokens < 0) {
            throw new IllegalArgumentException("Maximum tokens must not be negative");
        }
        if (estimator.estimate(renderer.apply(text)) <= maxTokens) {
            return Optional.of(new TruncatedText(text, false));
        }
        if (estimator.estimate(renderer.apply(MARKER)) > maxTokens) {
            return Optional.empty();
        }

        // 二分截断
        int low = 0;
        int high = text.length();
        int best = 0;
        while (low <= high) {
            int probe = (low + high) >>> 1;
            int boundary = safePrefixBoundary(text, probe);
            String candidate = text.substring(0, boundary) + MARKER;
            if (estimator.estimate(renderer.apply(candidate)) <= maxTokens) {
                best = boundary;
                low = probe + 1;
            } else {
                high = probe - 1;
            }
        }
        return Optional.of(new TruncatedText(text.substring(0, best) + MARKER, true));
    }

    private static int safePrefixBoundary(String text, int index) {
        if (index > 0 && index < text.length()
                && Character.isLowSurrogate(text.charAt(index))) {
            return index - 1;
        }
        return index;
    }

    public record TruncatedText(String text, boolean truncated) {
    }
}
