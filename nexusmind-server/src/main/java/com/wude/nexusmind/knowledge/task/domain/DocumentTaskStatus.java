package com.wude.nexusmind.knowledge.task.domain;

public enum DocumentTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean active() {
        return this == PENDING || this == RUNNING;
    }
}
