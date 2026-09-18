package com.wude.nexusmind.agent.application;

public final class AgentExecutionException extends RuntimeException {

    private final String code;
    private final String clientMessage;

    private AgentExecutionException(String code, String clientMessage, Throwable cause) {
        super(clientMessage, cause);
        this.code = code;
        this.clientMessage = clientMessage;
    }

    public static AgentExecutionException timeout() {
        return new AgentExecutionException(
                "AGENT_TIMEOUT", "Agent 执行超时，请稍后重试", null);
    }

    public static AgentExecutionException toolLimit() {
        return new AgentExecutionException(
                "AGENT_TOOL_LIMIT_EXCEEDED", "Agent 工具调用次数超过限制", null);
    }

    public static AgentExecutionException model(Throwable cause) {
        return new AgentExecutionException(
                "AGENT_MODEL_ERROR", "模型生成失败，请稍后重试", cause);
    }

    public static AgentExecutionException tool(Throwable cause) {
        return new AgentExecutionException(
                "AGENT_TOOL_ERROR", "知识库工具执行失败，请稍后重试", cause);
    }

    public static AgentExecutionException mcpTool(Throwable cause) {
        return new AgentExecutionException(
                "AGENT_MCP_TOOL_ERROR", "MCP tool execution failed", cause);
    }

    public static AgentExecutionException internal(Throwable cause) {
        return new AgentExecutionException(
                "AGENT_INTERNAL_ERROR", "Agent 执行失败，请稍后重试", cause);
    }

    public static AgentExecutionException sessionBusy() {
        return new AgentExecutionException(
                "AGENT_SESSION_BUSY", "当前会话已有 Agent 请求正在执行，请稍后重试", null);
    }

    public static AgentExecutionException leaseLost(Throwable cause) {
        return new AgentExecutionException(
                "AGENT_SESSION_LEASE_LOST", "当前 Agent 运行已失去会话所有权", cause);
    }

    public static AgentExecutionException contextBudget(Throwable cause) {
        return new AgentExecutionException(
                "AGENT_CONTEXT_BUDGET_EXCEEDED",
                "当前对话超过 Agent 上下文预算，请开始新会话或缩短问题后重试", cause);
    }

    public static AgentExecutionException contextBudget() {
        return contextBudget(null);
    }

    public String code() {
        return code;
    }

    public String clientMessage() {
        return clientMessage;
    }
}
