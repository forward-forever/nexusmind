package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.retrieval.RagRetrievalProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RagChatProperties.class, RagRetrievalProperties.class})
public class RagConfiguration {
}
