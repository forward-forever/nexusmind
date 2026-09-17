package com.wude.nexusmind.knowledge.task;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(DocumentTaskProperties.class)
@ConditionalOnProperty(name = "nexusmind.document-task.enabled", havingValue = "true")
public class DocumentTaskConfiguration {

    @Bean
    InitializingBean documentTaskPropertiesValidator(DocumentTaskProperties properties) {
        return properties::validate;
    }

    @Bean(name = "documentTaskExecutor")
    ThreadPoolTaskExecutor documentTaskExecutor(DocumentTaskProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerConcurrency());
        executor.setMaxPoolSize(properties.getWorkerConcurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("document-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.toIntExact(properties.getShutdownAwait().toSeconds()));
        executor.initialize();
        return executor;
    }

    @Bean(name = "documentTaskWorkerId")
    String documentTaskWorkerId() {
        String process = ManagementFactory.getRuntimeMXBean().getName();
        return process + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock documentTaskClock() {
        return Clock.systemUTC();
    }
}
