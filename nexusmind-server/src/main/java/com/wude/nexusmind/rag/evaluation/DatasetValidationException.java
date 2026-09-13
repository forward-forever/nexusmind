package com.wude.nexusmind.rag.evaluation;

public class DatasetValidationException extends RuntimeException {

    public DatasetValidationException(String message) {
        super(message);
    }

    public DatasetValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
