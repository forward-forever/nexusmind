package com.wude.nexusmind.config;

import com.wude.nexusmind.knowledge.service.DocumentIndexStateService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.index.DocumentIndexingService;
import com.wude.nexusmind.rag.index.KnowledgeBaseIndexRebuildCli;
import com.wude.nexusmind.rag.index.KnowledgeBaseIndexRebuildService;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.milvus.MilvusCollectionAdmin;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nexusmind.rebuild.enabled", havingValue = "true")
public class IndexRebuildConfiguration {

    @Bean
    KnowledgeBaseIndexRebuildService knowledgeBaseIndexRebuildService(
            KnowledgeBaseService knowledgeBaseService,
            DocumentService documentService,
            DocumentIndexStateService indexStateService,
            DocumentIndexingService indexingService,
            EmbeddingBatchService embeddingService,
            MilvusCollectionAdmin collectionAdmin,
            MilvusCollectionNamingStrategy namingStrategy) {
        return new KnowledgeBaseIndexRebuildService(
                knowledgeBaseService,
                documentService,
                indexStateService,
                indexingService,
                embeddingService,
                collectionAdmin,
                namingStrategy);
    }

    @Bean
    KnowledgeBaseIndexRebuildCli knowledgeBaseIndexRebuildCli(
            ConfigurableApplicationContext applicationContext,
            KnowledgeBaseIndexRebuildService rebuildService) {
        return new KnowledgeBaseIndexRebuildCli(applicationContext, rebuildService);
    }
}
