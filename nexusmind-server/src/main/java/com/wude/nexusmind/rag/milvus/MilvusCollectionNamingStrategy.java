package com.wude.nexusmind.rag.milvus;

import org.springframework.stereotype.Component;

@Component
public class MilvusCollectionNamingStrategy {

    public String forKnowledgeBase(long knowledgeBaseId) {
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("Knowledge base ID must be positive");
        }
        return "kb_" + knowledgeBaseId;
    }
}
