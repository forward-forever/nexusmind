package com.wude.nexusmind.agent.tool;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public final class AgentToolSet {

    private final List<ToolCallback> callbacks;

    public AgentToolSet(KnowledgeSearchTool knowledgeSearchTool) {
        this.callbacks = List.of(ToolCallbacks.from(knowledgeSearchTool));
        if (callbacks.size() != 1) {
            throw new IllegalStateException("Checkpoint 12 must expose exactly one agent tool");
        }
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    public ToolCallback knowledgeSearchCallback() {
        return callbacks.get(0);
    }
}
