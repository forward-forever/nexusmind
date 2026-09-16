package com.wude.nexusmind.agent.config;

import com.wude.nexusmind.rag.retrieval.RetrievalLimits;
import com.wude.nexusmind.rag.retrieval.RetrievalServiceRegistry;

public final class AgentPolicyValidator {

    public AgentPolicyValidator(AgentProperties properties,
                                RetrievalServiceRegistry retrievalServiceRegistry) {
        if (properties.knowledgeSearch().topK() > RetrievalLimits.MAX_PUBLIC_TOP_K) {
            throw new IllegalArgumentException(
                    "Agent knowledge-search topK must not exceed retrieval max topK "
                            + RetrievalLimits.MAX_PUBLIC_TOP_K);
        }
        retrievalServiceRegistry.get(properties.knowledgeSearch().retriever());
    }
}
