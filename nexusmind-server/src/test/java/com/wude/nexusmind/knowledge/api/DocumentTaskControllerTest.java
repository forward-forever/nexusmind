package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.task.DocumentTaskEnqueueResult;
import com.wude.nexusmind.knowledge.task.DocumentTaskService;
import com.wude.nexusmind.knowledge.task.domain.DocumentTaskStatus;
import com.wude.nexusmind.knowledge.task.domain.DocumentTaskType;
import com.wude.nexusmind.knowledge.task.infrastructure.persistence.KnowledgeDocumentTask;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentTaskControllerTest {

    @Test
    void returnsAcceptedForNewOrActiveTask() {
        DocumentTaskService service = mock(DocumentTaskService.class);
        when(service.enqueueProcess(7L)).thenReturn(new DocumentTaskEnqueueResult(task(), true));

        var response = new DocumentTaskController(service).process(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).extracting(DocumentTaskResponse::taskId).isEqualTo(11L);
    }

    @Test
    void returnsOkForAlreadyCompletedBusinessOperation() {
        DocumentTaskService service = mock(DocumentTaskService.class);
        KnowledgeDocumentTask task = task();
        task.setStatus(DocumentTaskStatus.SUCCEEDED);
        when(service.enqueueIndex(7L)).thenReturn(new DocumentTaskEnqueueResult(task, false));

        assertThat(new DocumentTaskController(service).index(7L).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static KnowledgeDocumentTask task() {
        KnowledgeDocumentTask task = new KnowledgeDocumentTask();
        task.setId(11L);
        task.setDocumentId(7L);
        task.setKnowledgeBaseId(3L);
        task.setTaskType(DocumentTaskType.PROCESS);
        task.setStatus(DocumentTaskStatus.PENDING);
        task.setAttemptCount(0);
        task.setRecoveryCount(0);
        task.setEnqueuedAt(LocalDateTime.of(2026, 9, 17, 10, 0));
        return task;
    }
}
