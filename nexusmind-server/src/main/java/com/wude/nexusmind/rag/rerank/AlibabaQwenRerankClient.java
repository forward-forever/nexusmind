package com.wude.nexusmind.rag.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wude.nexusmind.model.config.RerankProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.function.LongSupplier;

public class AlibabaQwenRerankClient implements RerankClient {

    static final String RERANK_PATH = "services/rerank/text-rerank/text-rerank";

    private static final Logger LOGGER = LoggerFactory.getLogger(AlibabaQwenRerankClient.class);

    private final RestClient restClient;
    private final RerankProviderProperties properties;
    private final LongSupplier nanoTime;

    public AlibabaQwenRerankClient(RestClient restClient, RerankProviderProperties properties) {
        this(restClient, properties, System::nanoTime);
    }

    AlibabaQwenRerankClient(RestClient restClient,
                            RerankProviderProperties properties,
                            LongSupplier nanoTime) {
        this.restClient = restClient;
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    @Override
    public RerankResult rerank(String query, List<String> documents, int topN) {
        requireRequest(query, documents, topN);
        ApiRequest request = new ApiRequest(
                properties.model(),
                new Input(query, List.copyOf(documents)),
                new Parameters(topN, properties.instruct()));
        long startedAt = nanoTime.getAsLong();
        try {
            // https://help.aliyun.com/zh/model-studio/text-rerank-api
            ApiResponse response = restClient.post()
                    .uri(RERANK_PATH)
                    .body(request)
                    .retrieve()
                    .body(ApiResponse.class);
            long latencyMs = elapsedMillis(startedAt);
            RerankResult result = toResult(response, latencyMs);
            LOGGER.info("Rerank completed: model={}, candidates={}, returned={}, latencyMs={}, "
                            + "promptTokens={}, totalTokens={}",
                    properties.model(), documents.size(), result.items().size(), latencyMs,
                    result.usage() == null ? null : result.usage().promptTokens(),
                    result.usage() == null ? null : result.usage().totalTokens());
            return result;
        } catch (RestClientException exception) {
            long latencyMs = elapsedMillis(startedAt);
            LOGGER.error("Rerank provider call failed: model={}, candidates={}, latencyMs={}, errorType={}",
                    properties.model(), documents.size(), latencyMs,
                    exception.getClass().getSimpleName());
            throw new RerankClientException("Rerank provider request failed", exception);
        }
    }

    private RerankResult toResult(ApiResponse response, long latencyMs) {
        if (response == null) {
            throw new RerankClientException("Rerank provider returned an empty response body");
        }
        if (response.output() == null || response.output().results() == null) {
            String providerCode = response.code() == null ? "unknown" : response.code();
            throw new RerankClientException(
                    "Rerank provider response did not contain results; code=" + providerCode);
        }
        List<RerankResult.Item> items = response.output().results().stream()
                .map(item -> new RerankResult.Item(item.index(), item.relevanceScore()))
                .toList();
        RerankResult.Usage usage = response.usage() == null
                ? null
                : new RerankResult.Usage(
                        response.usage().promptTokens(), response.usage().totalTokens());
        return new RerankResult(items, usage, latencyMs);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (nanoTime.getAsLong() - startedAt) / 1_000_000L);
    }

    private static void requireRequest(String query, List<String> documents, int topN) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Rerank query is required");
        }
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Rerank documents are required");
        }
        if (documents.stream().anyMatch(document -> document == null || document.isBlank())) {
            throw new IllegalArgumentException("Rerank documents must not contain blank text");
        }
        if (topN <= 0) {
            throw new IllegalArgumentException("Rerank topN must be positive");
        }
    }

    private record ApiRequest(String model, Input input, Parameters parameters) {
    }

    private record Input(String query, List<String> documents) {
    }

    private record Parameters(
            @JsonProperty("top_n") int topN,
            String instruct) {
    }

    private record ApiResponse(Output output,
                               Usage usage,
                               @JsonProperty("request_id") String requestId,
                               String code,
                               String message) {
    }

    private record Output(List<ResultItem> results) {
    }

    private record ResultItem(
            int index,
            @JsonProperty("relevance_score") double relevanceScore) {
    }

    private record Usage(
            @JsonProperty("prompt_tokens") Long promptTokens,
            @JsonProperty("total_tokens") Long totalTokens) {
    }
}
