package com.wude.nexusmind.agent.memory;

public final class AgentSessionLeaseLostException extends RuntimeException {

    public AgentSessionLeaseLostException(String sessionId) {
        super("Agent session lease was lost for session " + sessionId);
    }
}
