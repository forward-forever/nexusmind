package com.wude.nexusmind.agent.stream;

import com.wude.nexusmind.agent.model.AgentSource;

import java.util.List;
import java.util.Map;

public record AgentStreamEvent(
        String type,
        String runId,
        String content,
        String invocationId,
        String toolName,
        Map<String, Object> arguments,
        Long durationMs,
        Integer resultCount,
        List<AgentSource> sources,
        String code,
        String message,
        Integer toolCallCount,
        Integer modelTurnCount) {

    public AgentStreamEvent {
        arguments = arguments == null ? null : Map.copyOf(arguments);
        sources = sources == null ? null : List.copyOf(sources);
    }

    public static AgentStreamEvent assistantDelta(String runId, String content) {
        return new AgentStreamEvent("assistant_delta", runId, content, null, null,
                null, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent toolStart(String runId,
                                             String invocationId,
                                             String toolName,
                                             Map<String, Object> arguments) {
        return new AgentStreamEvent("tool_start", runId, null, invocationId, toolName,
                arguments, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent toolResult(String runId,
                                              String invocationId,
                                              String toolName,
                                              long durationMs,
                                              int resultCount,
                                              List<AgentSource> sources) {
        return new AgentStreamEvent("tool_result", runId, null, invocationId, toolName,
                null, durationMs, resultCount, sources, null, null, null, null);
    }

    public static AgentStreamEvent toolError(String runId,
                                             String invocationId,
                                             String toolName,
                                             String code,
                                             String message) {
        return new AgentStreamEvent("tool_error", runId, null, invocationId, toolName,
                null, null, null, null, code, message, null, null);
    }

    public static AgentStreamEvent done(String runId,
                                        int toolCallCount,
                                        int modelTurnCount,
                                        long durationMs,
                                        List<AgentSource> sources) {
        return new AgentStreamEvent("done", runId, null, null, null,
                null, durationMs, null, sources, null, null,
                toolCallCount, modelTurnCount);
    }

    public static AgentStreamEvent error(String runId, String code, String message) {
        return new AgentStreamEvent("error", runId, null, null, null,
                null, null, null, null, code, message, null, null);
    }
}
