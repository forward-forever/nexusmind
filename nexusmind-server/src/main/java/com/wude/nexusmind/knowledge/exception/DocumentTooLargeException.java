package com.wude.nexusmind.knowledge.exception;

public class DocumentTooLargeException extends RuntimeException {

    public DocumentTooLargeException(long maximumBytes) {
        super("File exceeds the maximum allowed size of " + maximumBytes + " bytes");
    }
}
