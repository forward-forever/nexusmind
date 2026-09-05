package com.wude.nexusmind.knowledge.exception;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;

public class InvalidDocumentStateException extends RuntimeException {

    public InvalidDocumentStateException(long documentId, DocumentStatus current, String operation) {
        super("Document " + documentId + " cannot " + operation + " while status is " + current);
    }
}
