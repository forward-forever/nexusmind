package com.wude.nexusmind.knowledge.exception;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(long id) {
        super("Document not found: " + id);
    }
}
