package com.wude.nexusmind.agent.mcp;

import java.util.List;

public record McpDiscoveryResponse(boolean enabled,
                                   int discoveredCount,
                                   int allowedCount,
                                   List<McpDiscoveredTool> tools) {

    public McpDiscoveryResponse {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
