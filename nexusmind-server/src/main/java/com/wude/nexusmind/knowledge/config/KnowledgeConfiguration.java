package com.wude.nexusmind.knowledge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StorageProperties.class, ChunkingProperties.class})
public class KnowledgeConfiguration {
}
