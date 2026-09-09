package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.RagChatProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "nexusmind.rag.enabled", havingValue = "true")
public class RagWebMvcConfiguration implements WebMvcConfigurer {

    private final RagChatProperties properties;

    public RagWebMvcConfiguration(RagChatProperties properties) {
        this.properties = properties;
    }

    @Bean
    ThreadPoolTaskExecutor ragMvcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("rag-mvc-");
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(ragMvcTaskExecutor());
        configurer.setDefaultTimeout(properties.streamTimeout().toMillis());
    }
}
