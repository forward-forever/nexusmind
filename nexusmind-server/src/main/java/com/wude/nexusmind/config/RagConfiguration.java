package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.RagChatProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagChatProperties.class)
public class RagConfiguration {
}
