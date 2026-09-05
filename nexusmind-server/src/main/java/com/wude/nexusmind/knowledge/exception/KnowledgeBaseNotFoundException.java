package com.wude.nexusmind.knowledge.exception;

public class KnowledgeBaseNotFoundException extends RuntimeException {

    public KnowledgeBaseNotFoundException(long id) {
        super("Knowledge base not found: " + id);
    }
}
