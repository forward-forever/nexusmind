package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.EmbeddingProperties;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.index.EmbeddingBatchPlanner;
import com.wude.nexusmind.rag.milvus.DenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusDenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmbeddingProperties.class, MilvusProperties.class})
public class VectorConfiguration {

    @Bean
    @ConditionalOnProperty(name = "nexusmind.vector.enabled", havingValue = "true")
    EmbeddingBatchService embeddingBatchService(EmbeddingModel embeddingModel,
                                                EmbeddingProperties embeddingProperties) {
        return new EmbeddingBatchService(embeddingModel, embeddingProperties);
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
    DenseVectorIndex denseVectorIndex(MilvusClientV2 client, MilvusProperties properties) {
        return new MilvusDenseVectorIndex(client, properties);
    }
}
