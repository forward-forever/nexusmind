package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.RerankProviderProperties;
import com.wude.nexusmind.rag.rerank.AlibabaQwenRerankClient;
import com.wude.nexusmind.rag.rerank.RerankClient;
import com.wude.nexusmind.rag.retrieval.HybridRerankRetrievalService;
import com.wude.nexusmind.rag.retrieval.HybridRrfRetrievalService;
import com.wude.nexusmind.rag.retrieval.RerankCandidatePlanner;
import com.wude.nexusmind.rag.retrieval.RerankRetrievalProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RerankProviderProperties.class, RerankRetrievalProperties.class})
public class RerankConfiguration {

    @Bean
    @ConditionalOnProperty(name = "nexusmind.ai.rerank.enabled", havingValue = "true")
    RerankClient rerankClient(RestClient.Builder restClientBuilder,
                              RerankProviderProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        RestClient restClient = restClientBuilder.clone()
                .baseUrl(normalizeBaseUrl(properties.baseUrl()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .requestFactory(requestFactory)
                .build();
        return new AlibabaQwenRerankClient(restClient, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.ai.rerank.enabled", havingValue = "true")
    RerankCandidatePlanner rerankCandidatePlanner(RerankRetrievalProperties properties) {
        return new RerankCandidatePlanner(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.ai.rerank.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "openai")
    HybridRerankRetrievalService hybridRerankRetrievalService(
            HybridRrfRetrievalService hybridRetrievalService,
            RerankClient rerankClient,
            RerankCandidatePlanner candidatePlanner,
            RerankProviderProperties providerProperties) {
        return new HybridRerankRetrievalService(
                hybridRetrievalService, rerankClient, candidatePlanner, providerProperties);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
