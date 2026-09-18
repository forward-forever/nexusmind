package com.wude.nexusmind.agent.mcp;

public final class McpToolExecutionException extends RuntimeException {

    public McpToolExecutionException(String toolName) {
        super("MCP tool execution failed: " + toolName);
    }
}
