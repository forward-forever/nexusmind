package com.wude.nexusmind.agent.memory;

public final class AgentSessionNotFoundException extends RuntimeException {

    public AgentSessionNotFoundException(String sessionId) {
        super("Agent session not found: " + sessionId);
    }
}
