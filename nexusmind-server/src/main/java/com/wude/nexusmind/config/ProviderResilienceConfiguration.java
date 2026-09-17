package com.wude.nexusmind.config;

import com.wude.nexusmind.resilience.AiResilienceProperties;
import com.wude.nexusmind.resilience.ProviderFailureClassifier;
import com.wude.nexusmind.resilience.ProviderRetryExecutor;
import com.wude.nexusmind.resilience.ProviderStreamingRetry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiResilienceProperties.class)
public class ProviderResilienceConfiguration {

    @Bean
    ProviderFailureClassifier providerFailureClassifier() {
        return new ProviderFailureClassifier();
    }

    @Bean
    ProviderRetryExecutor providerRetryExecutor(AiResilienceProperties properties,
                                                ProviderFailureClassifier classifier) {
        return new ProviderRetryExecutor(properties, classifier);
    }

    @Bean
    ProviderStreamingRetry providerStreamingRetry(AiResilienceProperties properties,
                                                  ProviderFailureClassifier classifier) {
        return new ProviderStreamingRetry(properties, classifier);
    }
}
