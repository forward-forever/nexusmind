package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.EmbeddingProperties;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.index.EmbeddingBatchPlanner;
import com.wude.nexusmind.rag.milvus.MilvusDenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusProperties;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.rag.retrieval.RetrievalService;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.RetrievalVisibilityFilter;
import com.wude.nexusmind.rag.retrieval.Bm25RetrievalService;
import com.wude.nexusmind.rag.retrieval.DenseRetrievalService;
import com.wude.nexusmind.rag.retrieval.HybridRetrievalProperties;
import com.wude.nexusmind.rag.retrieval.HybridRouteCandidatePlanner;
import com.wude.nexusmind.rag.retrieval.HybridRrfRetrievalService;
import com.wude.nexusmind.rag.retrieval.ReciprocalRankFusion;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.ai.embedding.EmbeddingModel;
import com.wude.nexusmind.resilience.ProviderRetryExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmbeddingProperties.class, MilvusProperties.class})
public class VectorConfiguration {

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "openai")
    EmbeddingBatchService embeddingBatchService(EmbeddingModel embeddingModel,
                                                EmbeddingProperties embeddingProperties,
                                                ProviderRetryExecutor retryExecutor) {
        return new EmbeddingBatchService(embeddingModel, embeddingProperties, retryExecutor);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    EmbeddingBatchPlanner embeddingBatchPlanner(EmbeddingProperties embeddingProperties) {
        return new EmbeddingBatchPlanner(embeddingProperties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "nexusmind.milvus.enabled", havingValue = "true")
    MilvusClientV2 milvusClient(MilvusProperties properties) {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri(properties.uri())
                .connectTimeoutMs(properties.readyTimeout().toMillis())
                .rpcDeadlineMs(properties.readyTimeout().toMillis())
                .build());
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.milvus.enabled", havingValue = "true")
    MilvusDenseVectorIndex milvusRetrievalIndex(MilvusClientV2 client, MilvusProperties properties) {
        return new MilvusDenseVectorIndex(client, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    RetrievalVisibilityFilter retrievalVisibilityFilter(DocumentService documentService) {
        return new RetrievalVisibilityFilter(documentService);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    HybridRouteCandidatePlanner hybridRouteCandidatePlanner(HybridRetrievalProperties properties) {
        return new HybridRouteCandidatePlanner(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    ReciprocalRankFusion reciprocalRankFusion() {
        return new ReciprocalRankFusion();
    }

    @Bean(name = "retrievalRouteExecutor")
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    ThreadPoolTaskExecutor retrievalRouteExecutor(HybridRetrievalProperties properties) {
        HybridRetrievalProperties.Parallel parallel = properties.parallel();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("retrieval-route-");
        executor.setCorePoolSize(parallel.corePoolSize());
        executor.setMaxPoolSize(parallel.maxPoolSize());
        executor.setQueueCapacity(parallel.queueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "openai")
    HybridRrfRetrievalService hybridRrfRetrievalService(
            DenseRetrievalService denseRetrievalService,
            Bm25RetrievalService bm25RetrievalService,
            HybridRouteCandidatePlanner candidatePlanner,
            ReciprocalRankFusion fusion,
            HybridRetrievalProperties properties,
            @Qualifier("retrievalRouteExecutor") Executor routeExecutor) {
        return new HybridRrfRetrievalService(
                denseRetrievalService, bm25RetrievalService, candidatePlanner, fusion,
                properties, properties.parallel().enabled() ? routeExecutor : Runnable::run);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    RetrievalServiceRegistry retrievalServiceRegistry(java.util.List<RetrievalService> services) {
        return new RetrievalServiceRegistry(services);
    }
}
