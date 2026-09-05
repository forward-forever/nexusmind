package com.wude.nexusmind.knowledge.exception;

public class UnsupportedDocumentTypeException extends RuntimeException {

    public UnsupportedDocumentTypeException(String fileName) {
        super("Unsupported file type: " + fileName + ". Supported extensions: pdf, md, markdown, txt");
    }
}
