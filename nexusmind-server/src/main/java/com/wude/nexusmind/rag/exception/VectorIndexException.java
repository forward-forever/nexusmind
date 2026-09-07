package com.wude.nexusmind.rag.exception;

public class VectorIndexException extends RuntimeException {

    public VectorIndexException(String message) {
        super(message);
    }

    public VectorIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
