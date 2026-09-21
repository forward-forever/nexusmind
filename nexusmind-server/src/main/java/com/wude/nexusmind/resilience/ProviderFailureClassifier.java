package com.wude.nexusmind.resilience;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.OptionalInt;
import java.util.concurrent.TimeoutException;

/**
 *  失败分类器
 */
public final class ProviderFailureClassifier {

    public ProviderFailureCategory classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            Integer status = statusCodeOf(current);
            if (status != null) {
                return isRetryableStatus(status)
                        ? ProviderFailureCategory.RETRYABLE
                        : ProviderFailureCategory.NON_RETRYABLE;
            }
            if (current instanceof OpenAIInvalidDataException
                    || current instanceof IllegalArgumentException) {
                return ProviderFailureCategory.NON_RETRYABLE;
            }
            if (current instanceof OpenAIIoException
                    || current instanceof OpenAIRetryableException
                    || current instanceof ResourceAccessException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof TimeoutException
                    || current instanceof IOException) {
                return ProviderFailureCategory.RETRYABLE;
            }
            current = current.getCause();
        }
        return ProviderFailureCategory.NON_RETRYABLE;
    }

    public OptionalInt httpStatus(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            Integer status = statusCodeOf(current);
            if (status != null) {
                return OptionalInt.of(status);
            }
            current = current.getCause();
        }
        return OptionalInt.empty();
    }

    public String metricCategory(Throwable failure) {
        OptionalInt status = httpStatus(failure);
        if (status.isPresent()) {
            if (status.getAsInt() == 429) return "HTTP_429";
            if (status.getAsInt() >= 500) return "HTTP_5XX";
            return "HTTP_OTHER";
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return "TIMEOUT";
            }
            if (current instanceof IOException || current instanceof ResourceAccessException) {
                return "NETWORK";
            }
            current = current.getCause();
        }
        return "OTHER";
    }

    private static Integer statusCodeOf(Throwable failure) {
        if (failure instanceof RestClientResponseException response) {
            return response.getStatusCode().value();
        }
        if (failure instanceof OpenAIServiceException service) {
            return service.statusCode();
        }
        return null;
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500 && status <= 599;
    }
}
