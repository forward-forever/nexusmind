package com.wude.nexusmind.agent.mcp;

import com.wude.nexusmind.agent.config.AgentProperties;
import org.springframework.core.env.Environment;

public final class McpPolicyValidator {

    public McpPolicyValidator(McpProperties properties,
                              AgentProperties agentProperties,
                              Environment environment) {
        boolean springClientEnabled = environment.getProperty(
                "spring.ai.mcp.client.enabled", Boolean.class, false);
        if (properties.enabled() != springClientEnabled) {
            throw new IllegalStateException(
                    "nexusmind.mcp.enabled and spring.ai.mcp.client.enabled must match");
        }
        if (properties.enabled() && !agentProperties.enabled()) {
            throw new IllegalStateException("MCP requires NexusMind Agent to be enabled");
        }
        if (properties.requestTimeout().compareTo(agentProperties.maxDuration()) >= 0) {
            throw new IllegalStateException(
                    "MCP request timeout must be shorter than the Agent absolute duration");
        }
    }
}
