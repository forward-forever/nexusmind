package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.task.DocumentTaskStatus;
import com.wude.nexusmind.knowledge.task.DocumentTaskType;
import com.wude.nexusmind.knowledge.task.KnowledgeDocumentTask;

import java.time.LocalDateTime;

public record DocumentTaskResponse(
        long taskId,
        long documentId,
        DocumentTaskType taskType,
        DocumentTaskStatus status,
        int attemptCount,
        LocalDateTime enqueuedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String lastError
) {
    public static DocumentTaskResponse from(KnowledgeDocumentTask task) {
        return new DocumentTaskResponse(task.getId(), task.getDocumentId(), task.getTaskType(),
                task.getStatus(), task.getAttemptCount(), task.getEnqueuedAt(), task.getStartedAt(),
                task.getFinishedAt(), task.getLastError());
    }
}
