package com.wude.nexusmind.knowledge.exception;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;

public class InvalidDocumentIndexStateException extends RuntimeException {

    public InvalidDocumentIndexStateException(long documentId,
                                              DocumentStatus documentStatus,
                                              DocumentIndexStatus indexStatus,
                                              String operation) {
        super("Document %d cannot %s when status=%s and indexStatus=%s"
                .formatted(documentId, operation, documentStatus, indexStatus));
    }
}
