package com.wude.nexusmind.knowledge.task;

public class DocumentTaskNotFoundException extends RuntimeException {
    public DocumentTaskNotFoundException(long taskId) {
        super("Document task not found: " + taskId);
    }
}
