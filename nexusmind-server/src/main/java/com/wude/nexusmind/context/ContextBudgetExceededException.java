package com.wude.nexusmind.context;

public final class ContextBudgetExceededException extends RuntimeException {

    public ContextBudgetExceededException(String message) {
        super(message);
    }
}
