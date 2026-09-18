package com.wude.nexusmind.config;

import com.wude.nexusmind.context.NexusTokenEstimator;
import com.wude.nexusmind.context.SpringAiTokenEstimator;
import com.wude.nexusmind.context.TokenBudgetCalculator;
import com.wude.nexusmind.context.TokenBudgetProperties;
import com.wude.nexusmind.context.TokenTextTruncator;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TokenBudgetProperties.class)
public class TokenContextConfiguration {

    @Bean
    TokenCountEstimator springAiTokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    @Bean
    NexusTokenEstimator nexusTokenEstimator(TokenCountEstimator estimator) {
        return new SpringAiTokenEstimator(estimator);
    }

    @Bean
    TokenBudgetCalculator tokenBudgetCalculator(TokenBudgetProperties properties,
                                                NexusTokenEstimator estimator) {
        return new TokenBudgetCalculator(properties, estimator);
    }

    @Bean
    TokenTextTruncator tokenTextTruncator(NexusTokenEstimator estimator) {
        return new TokenTextTruncator(estimator);
    }
}
