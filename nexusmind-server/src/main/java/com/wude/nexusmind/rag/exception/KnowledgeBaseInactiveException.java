package com.wude.nexusmind.rag.exception;

public class KnowledgeBaseInactiveException extends RuntimeException {

    public KnowledgeBaseInactiveException(long knowledgeBaseId) {
        super("Knowledge base %d is not active".formatted(knowledgeBaseId));
    }
}
