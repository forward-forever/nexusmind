package com.wude.nexusmind.agent.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class NexusMcpToolNamePrefixGenerator implements McpToolNamePrefixGenerator {

    public static final int MAX_NAME_LENGTH = 64;

    @Override
    public String prefixedToolName(McpConnectionInfo connectionInfo, McpSchema.Tool tool) {
        String serverName = "server";
        if (connectionInfo != null && connectionInfo.initializeResult() != null
                && connectionInfo.initializeResult().serverInfo() != null) {
            serverName = connectionInfo.initializeResult().serverInfo().name();
        }
        return prefixedToolName(serverName, tool == null ? null : tool.name());
    }

    //  Spring AI 的 MCP 客户端在把远程工具包装成 ToolCallback 时,会调用这个 McpToolNamePrefixGenerator 来生成本地工具名。
    public String prefixedToolName(String serverName, String toolName) {
        String fullName = "mcp_" + sanitize(serverName) + "_" + sanitize(toolName);
        if (fullName.length() <= MAX_NAME_LENGTH) {
            return fullName;
        }
        String suffix = "_" + stableHash(fullName);
        return fullName.substring(0, MAX_NAME_LENGTH - suffix.length()) + suffix;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }

    private static String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
