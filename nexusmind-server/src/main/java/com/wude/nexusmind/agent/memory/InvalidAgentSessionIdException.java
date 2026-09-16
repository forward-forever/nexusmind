package com.wude.nexusmind.agent.memory;

public final class InvalidAgentSessionIdException extends IllegalArgumentException {

    public InvalidAgentSessionIdException() {
        super("sessionId must be a valid UUID");
    }
}
