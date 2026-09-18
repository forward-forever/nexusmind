package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.context.RagContextProperties;
import com.wude.nexusmind.rag.retrieval.RagRetrievalProperties;
import com.wude.nexusmind.rag.retrieval.HybridRetrievalProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        RagChatProperties.class,
        RagContextProperties.class,
        RagRetrievalProperties.class,
        HybridRetrievalProperties.class
})
public class RagConfiguration {
}
