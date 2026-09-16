package com.wude.nexusmind.config;

import com.wude.nexusmind.agent.application.AgentChatService;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.evaluation.AgentBehaviorDatasetLoader;
import com.wude.nexusmind.agent.evaluation.AgentBehaviorEvaluationCli;
import com.wude.nexusmind.agent.evaluation.AgentBehaviorEvaluationService;
import com.wude.nexusmind.agent.evaluation.AgentBehaviorExecutor;
import com.wude.nexusmind.agent.evaluation.AgentBehaviorReportWriter;
import com.wude.nexusmind.agent.evaluation.AgentChatBehaviorExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nexusmind.agent-evaluation.enabled", havingValue = "true")
public class AgentEvaluationConfiguration {

    @Bean
    AgentBehaviorDatasetLoader agentBehaviorDatasetLoader(ObjectMapper objectMapper) {
        return new AgentBehaviorDatasetLoader(objectMapper);
    }

    @Bean
    AgentBehaviorExecutor agentBehaviorExecutor(AgentChatService agentChatService,
                                                 AgentProperties properties) {
        return new AgentChatBehaviorExecutor(agentChatService, properties);
    }

    @Bean
    AgentBehaviorEvaluationService agentBehaviorEvaluationService(
            AgentBehaviorExecutor executor) {
        return new AgentBehaviorEvaluationService(executor, Clock.systemUTC());
    }

    @Bean
    AgentBehaviorReportWriter agentBehaviorReportWriter(ObjectMapper objectMapper) {
        return new AgentBehaviorReportWriter(objectMapper);
    }

    @Bean
    AgentBehaviorEvaluationCli agentBehaviorEvaluationCli(
            ConfigurableApplicationContext applicationContext,
            AgentBehaviorDatasetLoader datasetLoader,
            AgentBehaviorEvaluationService evaluationService,
            AgentBehaviorReportWriter reportWriter) {
        return new AgentBehaviorEvaluationCli(
                applicationContext, datasetLoader, evaluationService, reportWriter);
    }
}
