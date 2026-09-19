package com.wude.nexusmind.knowledge.task;

import com.wude.nexusmind.knowledge.task.domain.DocumentTaskStatus;
import com.wude.nexusmind.knowledge.task.domain.DocumentTaskType;
import com.wude.nexusmind.knowledge.task.infrastructure.persistence.DocumentTaskMapper;
import com.wude.nexusmind.knowledge.task.infrastructure.persistence.KnowledgeDocumentTask;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentIndexStateException;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentTaskServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 10, 0);

    @Test
    void enqueuesProcessWithoutChangingDocumentToProcessing() {
        Fixture fixture = fixture(document(DocumentStatus.UPLOADED, DocumentIndexStatus.NOT_INDEXED));
        when(fixture.taskMapper.findByDocumentAndTypeForUpdate(7L, DocumentTaskType.PROCESS))
                .thenReturn(Optional.empty());
        when(fixture.taskMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<KnowledgeDocumentTask>getArgument(0).setId(100L);
            return 1;
        });

        DocumentTaskEnqueueResult result = fixture.service.enqueueProcess(7L);

        assertThat(result.accepted()).isTrue();
        assertThat(result.task().getStatus()).isEqualTo(DocumentTaskStatus.PENDING);
        assertThat(result.task().getId()).isEqualTo(100L);
        verify(fixture.documentMapper, never()).updateStatus(anyLong(), any(), anyInt(), any());
    }

    @Test
    void returnsSameActiveLogicalTaskForDuplicateEnqueue() {
        Fixture fixture = fixture(document(DocumentStatus.UPLOADED, DocumentIndexStatus.NOT_INDEXED));
        KnowledgeDocumentTask existing = task(DocumentTaskType.PROCESS, DocumentTaskStatus.PENDING, 0);
        when(fixture.taskMapper.findByDocumentAndTypeForUpdate(7L, DocumentTaskType.PROCESS))
                .thenReturn(Optional.of(existing));

        DocumentTaskEnqueueResult result = fixture.service.enqueueProcess(7L);

        assertThat(result.task().getId()).isEqualTo(99L);
        verify(fixture.taskMapper, never()).insert(any());
        verify(fixture.taskMapper, never()).requeue(anyLong(), any(), anyBoolean());
    }

    @Test
    void readyProcessIsReconciledAsCompletedWithoutReprocessing() {
        Fixture fixture = fixture(document(DocumentStatus.READY, DocumentIndexStatus.NOT_INDEXED));
        when(fixture.taskMapper.findByDocumentAndTypeForUpdate(7L, DocumentTaskType.PROCESS))
                .thenReturn(Optional.empty());
        when(fixture.taskMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<KnowledgeDocumentTask>getArgument(0).setId(101L);
            return 1;
        });

        DocumentTaskEnqueueResult result = fixture.service.enqueueProcess(7L);

        assertThat(result.accepted()).isFalse();
        assertThat(result.task().getStatus()).isEqualTo(DocumentTaskStatus.SUCCEEDED);
    }

    @Test
    void indexRequiresReadyDocument() {
        Fixture fixture = fixture(document(DocumentStatus.UPLOADED, DocumentIndexStatus.NOT_INDEXED));
        assertThatThrownBy(() -> fixture.service.enqueueIndex(7L))
                .isInstanceOf(InvalidDocumentIndexStateException.class);
    }

    @Test
    void failedIndexRequeuesSameTaskAndResetsRecoveryCycle() {
        Fixture fixture = fixture(document(DocumentStatus.READY, DocumentIndexStatus.FAILED));
        KnowledgeDocumentTask existing = task(DocumentTaskType.INDEX, DocumentTaskStatus.FAILED, 2);
        when(fixture.taskMapper.findByDocumentAndTypeForUpdate(7L, DocumentTaskType.INDEX))
                .thenReturn(Optional.of(existing));
        KnowledgeDocumentTask pending = task(DocumentTaskType.INDEX, DocumentTaskStatus.PENDING, 0);
        when(fixture.taskMapper.findById(99L)).thenReturn(Optional.of(pending));

        DocumentTaskEnqueueResult result = fixture.service.enqueueIndex(7L);

        assertThat(result.task().getId()).isEqualTo(99L);
        verify(fixture.taskMapper).requeue(99L, NOW, true);
    }

    @Test
    void claimUsesNewRunTokenAndIncrementsAttemptInPersistence() {
        Fixture fixture = fixture(document(DocumentStatus.UPLOADED, DocumentIndexStatus.NOT_INDEXED));
        KnowledgeDocumentTask pending = task(DocumentTaskType.PROCESS, DocumentTaskStatus.PENDING, 0);
        KnowledgeDocumentTask running = task(DocumentTaskType.PROCESS, DocumentTaskStatus.RUNNING, 0);
        running.setRunToken("new-token");
        when(fixture.taskMapper.findNextPendingForUpdate()).thenReturn(Optional.of(pending));
        when(fixture.taskMapper.claim(anyLong(), any(), any(), any())).thenReturn(1);
        when(fixture.taskMapper.findById(99L)).thenReturn(Optional.of(running));

        assertThat(fixture.service.claimNext("worker-A")).contains(running);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(fixture.taskMapper).claim(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.eq("worker-A"), token.capture(),
                org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(token.getValue()).isNotBlank();
    }

    @Test
    void runTokenFencesHeartbeatAndCompletion() {
        Fixture fixture = fixture(document(DocumentStatus.UPLOADED, DocumentIndexStatus.NOT_INDEXED));
        when(fixture.taskMapper.heartbeat(99L, "old", NOW)).thenReturn(0);
        when(fixture.taskMapper.markSucceeded(99L, "old", NOW)).thenReturn(0);

        assertThat(fixture.service.heartbeat(99L, "old")).isFalse();
        assertThat(fixture.service.markSucceeded(99L, "old")).isFalse();
    }

    @Test
    void staleProcessWithReadyDocumentBecomesSucceededWithoutRequeue() {
        Fixture fixture = staleFixture(DocumentTaskType.PROCESS,
                document(DocumentStatus.READY, DocumentIndexStatus.NOT_INDEXED), 0);

        assertThat(fixture.service.reconcileStale(99L))
                .isEqualTo(DocumentTaskService.RecoveryOutcome.SUCCEEDED);
        verify(fixture.taskMapper).reconcileSucceeded(99L, NOW);
        verify(fixture.taskMapper, never()).recoverToPending(anyLong(), any(), any());
    }

    @Test
    void staleIndexWithIndexedDocumentBecomesSucceededWithoutReembedding() {
        Fixture fixture = staleFixture(DocumentTaskType.INDEX,
                document(DocumentStatus.READY, DocumentIndexStatus.INDEXED), 0);

        assertThat(fixture.service.reconcileStale(99L))
                .isEqualTo(DocumentTaskService.RecoveryOutcome.SUCCEEDED);
        verify(fixture.taskMapper).reconcileSucceeded(99L, NOW);
    }

    @Test
    void staleProcessingReturnsDocumentAndTaskToRetryableStates() {
        Fixture fixture = staleFixture(DocumentTaskType.PROCESS,
                document(DocumentStatus.PROCESSING, DocumentIndexStatus.NOT_INDEXED), 0);

        assertThat(fixture.service.reconcileStale(99L))
                .isEqualTo(DocumentTaskService.RecoveryOutcome.REQUEUED);
        verify(fixture.documentMapper).updateStatus(7L, DocumentStatus.UPLOADED, 0, null);
        verify(fixture.taskMapper).recoverToPending(99L, NOW, "Recovered stale PROCESS execution");
    }

    @Test
    void staleIndexingReturnsProjectionStateToNotIndexed() {
        Fixture fixture = staleFixture(DocumentTaskType.INDEX,
                document(DocumentStatus.READY, DocumentIndexStatus.INDEXING), 0);

        assertThat(fixture.service.reconcileStale(99L))
                .isEqualTo(DocumentTaskService.RecoveryOutcome.REQUEUED);
        verify(fixture.documentMapper).updateIndexStatus(7L, DocumentIndexStatus.NOT_INDEXED, null);
    }

    @Test
    void recoveryLimitTerminatesCrashLoop() {
        Fixture fixture = staleFixture(DocumentTaskType.PROCESS,
                document(DocumentStatus.PROCESSING, DocumentIndexStatus.NOT_INDEXED), 3);

        assertThat(fixture.service.reconcileStale(99L))
                .isEqualTo(DocumentTaskService.RecoveryOutcome.FAILED);
        verify(fixture.documentMapper).updateStatus(7L, DocumentStatus.FAILED, 0,
                DocumentTaskService.STALE_RECOVERY_LIMIT_EXCEEDED);
        verify(fixture.taskMapper).reconcileFailed(99L,
                DocumentTaskService.STALE_RECOVERY_LIMIT_EXCEEDED, NOW);
    }

    @Test
    void orphanProcessingCreatesPendingLogicalTask() {
        Fixture fixture = fixture(document(DocumentStatus.PROCESSING, DocumentIndexStatus.NOT_INDEXED));
        when(fixture.taskMapper.findByDocumentAndTypeForUpdate(7L, DocumentTaskType.PROCESS))
                .thenReturn(Optional.empty());

        assertThat(fixture.service.recoverOrphanProcess(7L)).isTrue();
        verify(fixture.documentMapper).updateStatus(7L, DocumentStatus.UPLOADED, 0, null);
        verify(fixture.taskMapper).insert(any(KnowledgeDocumentTask.class));
    }

    private static Fixture staleFixture(DocumentTaskType type, KnowledgeDocument document, int recoveries) {
        Fixture fixture = fixture(document);
        KnowledgeDocumentTask task = task(type, DocumentTaskStatus.RUNNING, recoveries);
        task.setHeartbeatAt(NOW.minusMinutes(10));
        when(fixture.taskMapper.findById(99L)).thenReturn(Optional.of(task));
        when(fixture.taskMapper.findByIdForUpdate(99L)).thenReturn(Optional.of(task));
        return fixture;
    }

    private static Fixture fixture(KnowledgeDocument document) {
        DocumentTaskMapper taskMapper = mock(DocumentTaskMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        when(documentMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(document));
        DocumentTaskProperties properties = new DocumentTaskProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC);
        return new Fixture(new DocumentTaskService(taskMapper, documentMapper, properties, clock),
                taskMapper, documentMapper);
    }

    private static KnowledgeDocument document(DocumentStatus status, DocumentIndexStatus indexStatus) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(7L);
        document.setKnowledgeBaseId(3L);
        document.setStatus(status);
        document.setIndexStatus(indexStatus);
        document.setChunkCount(0);
        return document;
    }

    private static KnowledgeDocumentTask task(DocumentTaskType type, DocumentTaskStatus status, int recoveries) {
        KnowledgeDocumentTask task = new KnowledgeDocumentTask();
        task.setId(99L);
        task.setDocumentId(7L);
        task.setKnowledgeBaseId(3L);
        task.setTaskType(type);
        task.setStatus(status);
        task.setAttemptCount(1);
        task.setRecoveryCount(recoveries);
        return task;
    }

    private record Fixture(DocumentTaskService service, DocumentTaskMapper taskMapper,
                           KnowledgeDocumentMapper documentMapper) {
    }
}
