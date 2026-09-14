package com.wude.nexusmind.config;

import com.wude.nexusmind.knowledge.config.ChunkingProperties;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.evaluation.RetrievalDatasetLoader;
import com.wude.nexusmind.rag.evaluation.RetrievalDatasetValidator;
import com.wude.nexusmind.rag.evaluation.RetrievalEvaluationCli;
import com.wude.nexusmind.rag.evaluation.RetrievalEvaluationService;
import com.wude.nexusmind.rag.evaluation.RetrievalMetricsCalculator;
import com.wude.nexusmind.rag.evaluation.RetrievalReportWriter;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import com.wude.nexusmind.rag.retrieval.HybridRetrievalProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nexusmind.evaluation.enabled", havingValue = "true")
public class EvaluationConfiguration {

    @Bean
    RetrievalDatasetLoader retrievalDatasetLoader(ObjectMapper objectMapper) {
        return new RetrievalDatasetLoader(objectMapper);
    }

    @Bean
    RetrievalMetricsCalculator retrievalMetricsCalculator() {
        return new RetrievalMetricsCalculator();
    }

    @Bean
    RetrievalDatasetValidator retrievalDatasetValidator(
            KnowledgeBaseService knowledgeBaseService,
            ChunkService chunkService,
            DocumentService documentService) {
        return new RetrievalDatasetValidator(knowledgeBaseService, chunkService, documentService);
    }

    @Bean
    RetrievalEvaluationService retrievalEvaluationService(
            RetrievalServiceRegistry retrievalServiceRegistry,
            RetrievalDatasetValidator datasetValidator,
            RetrievalMetricsCalculator metricsCalculator,
            ChunkingProperties chunkingProperties,
            HybridRetrievalProperties hybridProperties) {
        return new RetrievalEvaluationService(
                retrievalServiceRegistry,
                datasetValidator,
                metricsCalculator,
                chunkingProperties,
                hybridProperties,
                Clock.systemUTC(),
                System::nanoTime);
    }

    @Bean
    RetrievalReportWriter retrievalReportWriter(ObjectMapper objectMapper) {
        return new RetrievalReportWriter(objectMapper);
    }

    @Bean
    RetrievalEvaluationCli retrievalEvaluationCli(
            ConfigurableApplicationContext applicationContext,
            RetrievalDatasetLoader datasetLoader,
            RetrievalEvaluationService evaluationService,
            RetrievalReportWriter reportWriter) {
        return new RetrievalEvaluationCli(
                applicationContext, datasetLoader, evaluationService, reportWriter);
    }
}
