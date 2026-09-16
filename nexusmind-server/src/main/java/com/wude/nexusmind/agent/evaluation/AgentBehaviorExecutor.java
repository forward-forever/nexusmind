package com.wude.nexusmind.agent.evaluation;

public interface AgentBehaviorExecutor {

    AgentBehaviorRunResult execute(long knowledgeBaseId, String sessionId, String message);
}
