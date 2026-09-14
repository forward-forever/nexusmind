package com.wude.nexusmind.config;

import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.rag.evaluation.BenchmarkDatasetResolverCli;
import com.wude.nexusmind.rag.evaluation.BenchmarkDatasetResolverService;
import com.wude.nexusmind.rag.evaluation.BenchmarkGoldenDatasetWriter;
import com.wude.nexusmind.rag.evaluation.BenchmarkSourceDatasetLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nexusmind.dataset-resolver.enabled", havingValue = "true")
public class DatasetResolverConfiguration {

    @Bean
    BenchmarkSourceDatasetLoader benchmarkSourceDatasetLoader(ObjectMapper objectMapper) {
        return new BenchmarkSourceDatasetLoader(objectMapper);
    }

    @Bean
    BenchmarkDatasetResolverService benchmarkDatasetResolverService(
            DocumentService documentService,
            KnowledgeChunkMapper chunkMapper) {
        return new BenchmarkDatasetResolverService(documentService, chunkMapper);
    }

    @Bean
    BenchmarkGoldenDatasetWriter benchmarkGoldenDatasetWriter(ObjectMapper objectMapper) {
        return new BenchmarkGoldenDatasetWriter(objectMapper);
    }

    @Bean
    BenchmarkDatasetResolverCli benchmarkDatasetResolverCli(
            ConfigurableApplicationContext applicationContext,
            BenchmarkSourceDatasetLoader sourceLoader,
            BenchmarkDatasetResolverService resolverService,
            BenchmarkGoldenDatasetWriter goldenDatasetWriter) {
        return new BenchmarkDatasetResolverCli(
                applicationContext, sourceLoader, resolverService, goldenDatasetWriter);
    }
}
