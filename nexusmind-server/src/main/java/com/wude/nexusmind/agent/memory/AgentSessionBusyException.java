package com.wude.nexusmind.agent.memory;

public final class AgentSessionBusyException extends RuntimeException {

    private final String sessionId;

    public AgentSessionBusyException(String sessionId) {
        super("Agent session already has an active run");
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
