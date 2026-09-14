package com.wude.nexusmind.rag.rerank;

public class RerankClientException extends RuntimeException {

    public RerankClientException(String message) {
        super(message);
    }

    public RerankClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
