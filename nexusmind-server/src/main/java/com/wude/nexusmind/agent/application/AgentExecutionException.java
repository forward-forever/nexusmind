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

    public static AgentExecutionException internal(Throwable cause) {
        return new AgentExecutionException(
                "AGENT_INTERNAL_ERROR", "Agent 执行失败，请稍后重试", cause);
    }

    public String code() {
        return code;
    }

    public String clientMessage() {
        return clientMessage;
    }
}
