package com.wude.nexusmind.agent.memory;

public final class AgentSessionKnowledgeBaseMismatchException extends RuntimeException {

    public AgentSessionKnowledgeBaseMismatchException(String sessionId, long knowledgeBaseId) {
        super("Agent session " + sessionId + " does not belong to knowledge base " + knowledgeBaseId);
    }
}
