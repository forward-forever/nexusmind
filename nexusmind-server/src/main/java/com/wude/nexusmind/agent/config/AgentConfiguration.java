package com.wude.nexusmind.agent.config;

import com.wude.nexusmind.agent.application.AgentModelTurnStreamer;
import com.wude.nexusmind.agent.application.DocumentContextService;
import com.wude.nexusmind.agent.application.SpringAiAgentModelTurnStreamer;
import com.wude.nexusmind.agent.prompt.AgentPromptFactory;
import com.wude.nexusmind.agent.memory.HistoricalCitationSanitizer;
import com.wude.nexusmind.agent.tool.AgentToolSet;
import com.wude.nexusmind.agent.tool.DocumentContextTool;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    @Bean
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    KnowledgeSearchTool knowledgeSearchTool(RetrievalServiceRegistry retrievalServiceRegistry,
                                            AgentProperties properties) {
        return new KnowledgeSearchTool(retrievalServiceRegistry, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    DocumentContextService documentContextService(KnowledgeChunkMapper chunkMapper,
                                                   KnowledgeDocumentMapper documentMapper) {
        return new DocumentContextService(chunkMapper, documentMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    DocumentContextTool documentContextTool(DocumentContextService contextService,
                                            AgentProperties properties) {
        return new DocumentContextTool(contextService, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    AgentToolSet agentToolSet(KnowledgeSearchTool knowledgeSearchTool,
                              DocumentContextTool documentContextTool) {
        return new AgentToolSet(knowledgeSearchTool, documentContextTool);
    }

    @Bean(name = "agentToolCallingManager")
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    ToolCallingManager agentToolCallingManager() {
        return ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(
                        new DefaultToolExecutionExceptionProcessor(true))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
    AgentModelTurnStreamer agentModelTurnStreamer(ChatClient.Builder builder) {
        return new SpringAiAgentModelTurnStreamer(builder);
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    AgentPromptFactory agentPromptFactory() {
        return new AgentPromptFactory();
    }

    @Bean
    HistoricalCitationSanitizer historicalCitationSanitizer() {
        return new HistoricalCitationSanitizer();
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    AgentPolicyValidator agentPolicyValidator(AgentProperties properties,
                                              RetrievalServiceRegistry retrievalServiceRegistry) {
        return new AgentPolicyValidator(properties, retrievalServiceRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    @ConditionalOnProperty(name = "nexusmind.agent.enabled", havingValue = "true")
    Clock agentClock() {
        return Clock.systemUTC();
    }
}
