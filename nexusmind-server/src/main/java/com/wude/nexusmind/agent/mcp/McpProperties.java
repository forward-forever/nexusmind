package com.wude.nexusmind.agent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("nexusmind.mcp")
public record McpProperties(boolean enabled,
                            List<String> allowedTools,
                            Duration requestTimeout) {

    public McpProperties {
        allowedTools = allowedTools == null ? List.of() : allowedTools.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("MCP request timeout must be positive");
        }
    }
}
