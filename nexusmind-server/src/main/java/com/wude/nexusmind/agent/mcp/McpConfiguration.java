package com.wude.nexusmind.agent.mcp;

import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.context.NexusTokenEstimator;
import com.wude.nexusmind.context.TokenTextTruncator;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;
import com.wude.nexusmind.observability.NexusMindMetrics;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpConfiguration {

    @Bean
    @ConditionalOnProperty(name = "nexusmind.mcp.enabled", havingValue = "true")
    McpToolNamePrefixGenerator nexusMcpToolNamePrefixGenerator() {
        return new NexusMcpToolNamePrefixGenerator();
    }

    @Bean
    @ConditionalOnProperty(name = "nexusmind.mcp.enabled", havingValue = "true")
    McpToolResultBudgeter mcpToolResultBudgeter(ObjectMapper objectMapper,
                                                 NexusTokenEstimator estimator,
                                                 TokenTextTruncator truncator,
                                                 AgentProperties agentProperties,
                                                 NexusMindMetrics metrics) {
        return new McpToolResultBudgeter(objectMapper, estimator, truncator, agentProperties, metrics);
    }

    @Bean
    McpPolicyValidator mcpPolicyValidator(McpProperties properties,
                                          AgentProperties agentProperties,
                                          Environment environment) {
        return new McpPolicyValidator(properties, agentProperties, environment);
    }

    @Bean
    McpToolRegistry mcpToolRegistry(
            McpProperties properties,
            ObjectProvider<SyncMcpToolCallbackProvider> provider,
            ObjectProvider<McpToolResultBudgeter> resultBudgeter) {
        return new McpToolRegistry(properties, provider, resultBudgeter);
    }
}
