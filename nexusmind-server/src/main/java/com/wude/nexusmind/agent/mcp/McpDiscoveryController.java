package com.wude.nexusmind.agent.mcp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/mcp")
public class McpDiscoveryController {

    private final McpToolRegistry registry;

    public McpDiscoveryController(McpToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/tools")
    public McpDiscoveryResponse tools() {
        return registry.discovery();
    }
}
