package com.wude.nexusmind.agent.tool;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class AgentToolSet {

    private final List<ToolCallback> callbacks;

    public AgentToolSet(KnowledgeSearchTool knowledgeSearchTool,
                        DocumentContextTool documentContextTool) {
        this.callbacks = Stream.concat(
                        Arrays.stream(ToolCallbacks.from(knowledgeSearchTool)),
                        Arrays.stream(ToolCallbacks.from(documentContextTool)))
                .toList();
        if (callbacks.size() != 2) {
            throw new IllegalStateException("Checkpoint 13 must expose exactly two agent tools");
        }
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    public ToolCallback knowledgeSearchCallback() {
        return callbacks.get(0);
    }

    public ToolCallback documentContextCallback() {
        return callbacks.get(1);
    }
}
