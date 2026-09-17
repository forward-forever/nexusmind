package com.wude.nexusmind.knowledge.task;

import com.wude.nexusmind.knowledge.service.DocumentProcessingService;
import com.wude.nexusmind.rag.index.DocumentIndexingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentTaskWorkerTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutdown() {
        if (executor != null) executor.shutdown();
    }

    @Test
    void movesClaimedProcessTaskThroughBusinessSuccessToTaskSuccess() throws Exception {
        DocumentTaskService taskService = mock(DocumentTaskService.class);
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        CountDownLatch completed = new CountDownLatch(1);
        KnowledgeDocumentTask task = runningTask(DocumentTaskType.PROCESS);
        when(taskService.claimNext("worker-test"))
                .thenReturn(Optional.of(task), Optional.empty());
        doAnswer(invocation -> {
            completed.countDown();
            return true;
        }).when(taskService).markSucceeded(10L, "token-A");

        DocumentTaskWorker worker = worker(taskService, processingService, mock(DocumentIndexingService.class));
        worker.poll();

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        verify(processingService).process(20L);
        verify(taskService).markSucceeded(10L, "token-A");
    }

    @Test
    void normalBusinessFailureMarksTaskFailedWithoutAutomaticRetry() throws Exception {
        DocumentTaskService taskService = mock(DocumentTaskService.class);
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        CountDownLatch failed = new CountDownLatch(1);
        KnowledgeDocumentTask task = runningTask(DocumentTaskType.PROCESS);
        when(taskService.claimNext("worker-test"))
                .thenReturn(Optional.of(task), Optional.empty());
        doThrow(new IllegalStateException("parser failed")).when(processingService).process(20L);
        doAnswer(invocation -> {
            failed.countDown();
            return true;
        }).when(taskService).markFailed(10L, "token-A", "PROCESS failed (IllegalStateException)");

        DocumentTaskWorker worker = worker(taskService, processingService, mock(DocumentIndexingService.class));
        worker.poll();

        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
        verify(taskService).markFailed(10L, "token-A", "PROCESS failed (IllegalStateException)");
        verify(taskService, org.mockito.Mockito.never()).claimNext("retry-worker");
    }

    private DocumentTaskWorker worker(DocumentTaskService taskService,
                                      DocumentProcessingService processingService,
                                      DocumentIndexingService indexingService) {
        DocumentTaskProperties properties = new DocumentTaskProperties();
        properties.setWorkerConcurrency(1);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        return new DocumentTaskWorker(taskService, processingService, indexingService,
                properties, executor, "worker-test");
    }

    private static KnowledgeDocumentTask runningTask(DocumentTaskType type) {
        KnowledgeDocumentTask task = new KnowledgeDocumentTask();
        task.setId(10L);
        task.setDocumentId(20L);
        task.setKnowledgeBaseId(30L);
        task.setTaskType(type);
        task.setStatus(DocumentTaskStatus.RUNNING);
        task.setAttemptCount(1);
        task.setRecoveryCount(0);
        task.setRunToken("token-A");
        return task;
    }
}
