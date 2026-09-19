package com.wude.nexusmind.observability;

import com.wude.nexusmind.agent.mcp.McpDiscoveryResponse;
import com.wude.nexusmind.agent.mcp.McpToolRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports the immutable startup MCP discovery state without remote health calls. */
@Component("mcp")
public final class McpHealthIndicator implements HealthIndicator {

    private final McpToolRegistry registry;

    public McpHealthIndicator(McpToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        McpDiscoveryResponse discovery = registry.discovery();
        return Health.up()
                .withDetail("enabled", discovery.enabled())
                .withDetail("discoveredToolCount", discovery.discoveredCount())
                .withDetail("allowedToolCount", discovery.allowedCount())
                .build();
    }
}
