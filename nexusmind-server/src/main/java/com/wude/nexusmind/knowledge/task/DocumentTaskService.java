package com.wude.nexusmind.knowledge.task;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.exception.DocumentNotFoundException;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentIndexStateException;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentStateException;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "nexusmind.document-task.enabled", havingValue = "true")
public class DocumentTaskService {

    public static final String STALE_RECOVERY_LIMIT_EXCEEDED = "STALE_RECOVERY_LIMIT_EXCEEDED";

    private final DocumentTaskMapper taskMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentTaskProperties properties;
    private final Clock clock;

    public DocumentTaskService(DocumentTaskMapper taskMapper,
                               KnowledgeDocumentMapper documentMapper,
                               DocumentTaskProperties properties,
                               Clock clock) {
        this.taskMapper = taskMapper;
        this.documentMapper = documentMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public DocumentTaskEnqueueResult enqueueProcess(long documentId) {
        KnowledgeDocument document = lockDocument(documentId);
        Optional<KnowledgeDocumentTask> existing = taskMapper.findByDocumentAndTypeForUpdate(
                documentId, DocumentTaskType.PROCESS);
        if (document.getStatus() == DocumentStatus.READY) {
            return new DocumentTaskEnqueueResult(ensureCompleted(document, DocumentTaskType.PROCESS, existing), false);
        }
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            KnowledgeDocumentTask task = existing.filter(value -> value.getStatus().active())
                    .orElseThrow(() -> new InvalidDocumentStateException(
                            documentId, document.getStatus(), "enqueue processing without an active task"));
            return new DocumentTaskEnqueueResult(task, true);
        }
        if (document.getStatus() != DocumentStatus.UPLOADED && document.getStatus() != DocumentStatus.FAILED) {
            throw new InvalidDocumentStateException(documentId, document.getStatus(), "enqueue processing");
        }
        return enqueue(document, DocumentTaskType.PROCESS, existing);
    }

    @Transactional
    public DocumentTaskEnqueueResult enqueueIndex(long documentId) {
        KnowledgeDocument document = lockDocument(documentId);
        if (document.getStatus() != DocumentStatus.READY) {
            throw new InvalidDocumentIndexStateException(documentId, document.getStatus(),
                    document.getIndexStatus(), "enqueue indexing");
        }
        Optional<KnowledgeDocumentTask> existing = taskMapper.findByDocumentAndTypeForUpdate(
                documentId, DocumentTaskType.INDEX);
        if (document.getIndexStatus() == DocumentIndexStatus.INDEXED) {
            return new DocumentTaskEnqueueResult(ensureCompleted(document, DocumentTaskType.INDEX, existing), false);
        }
        if (document.getIndexStatus() == DocumentIndexStatus.INDEXING) {
            KnowledgeDocumentTask task = existing.filter(value -> value.getStatus().active())
                    .orElseThrow(() -> new InvalidDocumentIndexStateException(
                            documentId, document.getStatus(), document.getIndexStatus(),
                            "enqueue indexing without an active task"));
            return new DocumentTaskEnqueueResult(task, true);
        }
        return enqueue(document, DocumentTaskType.INDEX, existing);
    }

    public KnowledgeDocumentTask get(long taskId) {
        return taskMapper.findById(taskId).orElseThrow(() -> new DocumentTaskNotFoundException(taskId));
    }

    public List<KnowledgeDocumentTask> listActive(long knowledgeBaseId) {
        return List.copyOf(taskMapper.findActiveByKnowledgeBaseId(knowledgeBaseId));
    }

    @Transactional
    public Optional<KnowledgeDocumentTask> claimNext(String workerId) {
        Optional<KnowledgeDocumentTask> candidate = taskMapper.findNextPendingForUpdate();
        if (candidate.isEmpty()) return Optional.empty();
        // 设置租约令牌
        String token = UUID.randomUUID().toString();
        LocalDateTime now = now();
        if (taskMapper.claim(candidate.get().getId(), workerId, token, now) != 1) return Optional.empty();
        return taskMapper.findById(candidate.get().getId());
    }

    @Transactional
    public boolean heartbeat(long taskId, String runToken) {
        return taskMapper.heartbeat(taskId, runToken, now()) == 1;
    }

    @Transactional
    public boolean markSucceeded(long taskId, String runToken) {
        return taskMapper.markSucceeded(taskId, runToken, now()) == 1;
    }

    @Transactional
    public boolean markFailed(long taskId, String runToken, String error) {
        return taskMapper.markFailed(taskId, runToken, safeError(error), now()) == 1;
    }

    public List<Long> findStaleTaskIds() {
        return List.copyOf(taskMapper.findStaleRunningIds(now().minus(properties.getStaleAfter())));
    }

    public List<Long> findOrphanProcessingDocumentIds() {
        return List.copyOf(taskMapper.findOrphanProcessingDocumentIds());
    }

    public List<Long> findOrphanIndexingDocumentIds() {
        return List.copyOf(taskMapper.findOrphanIndexingDocumentIds());
    }


    @Transactional
    public RecoveryOutcome reconcileStale(long taskId) {
        KnowledgeDocumentTask snapshot = taskMapper.findById(taskId).orElse(null);
        if (snapshot == null) {
            return RecoveryOutcome.UNCHANGED;
        }
        // Keep the same Document -> Task lock order used by enqueue operations.
        // The task is re-read under lock below, so a scan result that became fresh
        // or terminal in the meantime is left untouched.
        KnowledgeDocument document = lockDocument(snapshot.getDocumentId());
        KnowledgeDocumentTask task = taskMapper.findByIdForUpdate(taskId).orElse(null);
        if (task == null || task.getStatus() != DocumentTaskStatus.RUNNING
                || task.getHeartbeatAt() == null
                || !task.getHeartbeatAt().isBefore(now().minus(properties.getStaleAfter()))) {
            return RecoveryOutcome.UNCHANGED;
        }
        if (!Objects.equals(task.getDocumentId(), document.getId())) {
            return RecoveryOutcome.UNCHANGED;
        }
        return task.getTaskType() == DocumentTaskType.PROCESS
                ? reconcileProcess(task, document)
                : reconcileIndex(task, document);
    }

    @Transactional
    public boolean recoverOrphanProcess(long documentId) {
        KnowledgeDocument document = lockDocument(documentId);
        if (document.getStatus() != DocumentStatus.PROCESSING) return false;
        Optional<KnowledgeDocumentTask> existing = taskMapper.findByDocumentAndTypeForUpdate(
                documentId, DocumentTaskType.PROCESS);
        if (existing.filter(value -> value.getStatus().active()).isPresent()) return false;
        documentMapper.updateStatus(documentId, DocumentStatus.UPLOADED,
                document.getChunkCount() == null ? 0 : document.getChunkCount(), null);
        enqueueRecoveredOrphan(document, DocumentTaskType.PROCESS, existing);
        return true;
    }

    @Transactional
    public boolean recoverOrphanIndex(long documentId) {
        KnowledgeDocument document = lockDocument(documentId);
        if (document.getIndexStatus() != DocumentIndexStatus.INDEXING) return false;
        Optional<KnowledgeDocumentTask> existing = taskMapper.findByDocumentAndTypeForUpdate(
                documentId, DocumentTaskType.INDEX);
        if (existing.filter(value -> value.getStatus().active()).isPresent()) return false;
        documentMapper.updateIndexStatus(documentId, DocumentIndexStatus.NOT_INDEXED, null);
        enqueueRecoveredOrphan(document, DocumentTaskType.INDEX, existing);
        return true;
    }

    private DocumentTaskEnqueueResult enqueue(KnowledgeDocument document,
                                               DocumentTaskType type,
                                               Optional<KnowledgeDocumentTask> existing) {
        if (existing.isPresent() && existing.get().getStatus().active()) {
            return new DocumentTaskEnqueueResult(existing.get(), true);
        }
        KnowledgeDocumentTask task;
        if (existing.isEmpty()) {
            task = newTask(document, type, DocumentTaskStatus.PENDING);
            taskMapper.insert(task);
        } else {
            taskMapper.requeue(existing.get().getId(), now(), true);
            task = requiredTask(existing.get().getId());
        }
        return new DocumentTaskEnqueueResult(task, true);
    }

    private KnowledgeDocumentTask ensureCompleted(KnowledgeDocument document,
                                                  DocumentTaskType type,
                                                  Optional<KnowledgeDocumentTask> existing) {
        if (existing.isEmpty()) {
            KnowledgeDocumentTask task = newTask(document, type, DocumentTaskStatus.SUCCEEDED);
            task.setFinishedAt(now());
            taskMapper.insert(task);
            return task;
        }
        if (existing.get().getStatus() != DocumentTaskStatus.SUCCEEDED) {
            taskMapper.completeLogical(existing.get().getId(), now());
        }
        return requiredTask(existing.get().getId());
    }

    private RecoveryOutcome reconcileProcess(KnowledgeDocumentTask task, KnowledgeDocument document) {
        if (document.getStatus() == DocumentStatus.READY) {
            taskMapper.reconcileSucceeded(task.getId(), now());
            return RecoveryOutcome.SUCCEEDED;
        }
        if (document.getStatus() == DocumentStatus.FAILED) {
            taskMapper.reconcileFailed(task.getId(), safeError(document.getErrorMessage()), now());
            return RecoveryOutcome.FAILED;
        }
        if (recoveryLimitReached(task)) {
            if (document.getStatus() == DocumentStatus.PROCESSING) {
                documentMapper.updateStatus(document.getId(), DocumentStatus.FAILED,
                        document.getChunkCount() == null ? 0 : document.getChunkCount(),
                        STALE_RECOVERY_LIMIT_EXCEEDED);
            }
            taskMapper.reconcileFailed(task.getId(), STALE_RECOVERY_LIMIT_EXCEEDED, now());
            return RecoveryOutcome.FAILED;
        }
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            documentMapper.updateStatus(document.getId(), DocumentStatus.UPLOADED,
                    document.getChunkCount() == null ? 0 : document.getChunkCount(), null);
        }
        taskMapper.recoverToPending(task.getId(), now(), "Recovered stale PROCESS execution");
        return RecoveryOutcome.REQUEUED;
    }

    private RecoveryOutcome reconcileIndex(KnowledgeDocumentTask task, KnowledgeDocument document) {
        if (document.getIndexStatus() == DocumentIndexStatus.INDEXED) {
            taskMapper.reconcileSucceeded(task.getId(), now());
            return RecoveryOutcome.SUCCEEDED;
        }
        if (document.getIndexStatus() == DocumentIndexStatus.FAILED) {
            taskMapper.reconcileFailed(task.getId(), safeError(document.getIndexErrorMessage()), now());
            return RecoveryOutcome.FAILED;
        }
        if (recoveryLimitReached(task)) {
            if (document.getIndexStatus() == DocumentIndexStatus.INDEXING) {
                documentMapper.updateIndexStatus(document.getId(), DocumentIndexStatus.FAILED,
                        STALE_RECOVERY_LIMIT_EXCEEDED);
            }
            taskMapper.reconcileFailed(task.getId(), STALE_RECOVERY_LIMIT_EXCEEDED, now());
            return RecoveryOutcome.FAILED;
        }
        if (document.getIndexStatus() == DocumentIndexStatus.INDEXING) {
            documentMapper.updateIndexStatus(document.getId(), DocumentIndexStatus.NOT_INDEXED, null);
        }
        taskMapper.recoverToPending(task.getId(), now(), "Recovered stale INDEX execution");
        return RecoveryOutcome.REQUEUED;
    }

    private void enqueueRecoveredOrphan(KnowledgeDocument document, DocumentTaskType type,
                                        Optional<KnowledgeDocumentTask> existing) {
        if (existing.isEmpty()) {
            taskMapper.insert(newTask(document, type, DocumentTaskStatus.PENDING));
        } else {
            taskMapper.requeue(existing.get().getId(), now(), false);
        }
    }

    private boolean recoveryLimitReached(KnowledgeDocumentTask task) {
        return task.getRecoveryCount() >= properties.getMaxStaleRecoveries();
    }

    private KnowledgeDocument lockDocument(long documentId) {
        return documentMapper.findByIdForUpdate(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private KnowledgeDocumentTask requiredTask(long id) {
        return taskMapper.findById(id).orElseThrow(() -> new DocumentTaskNotFoundException(id));
    }

    private KnowledgeDocumentTask newTask(KnowledgeDocument document,
                                          DocumentTaskType type,
                                          DocumentTaskStatus status) {
        KnowledgeDocumentTask task = new KnowledgeDocumentTask();
        task.setKnowledgeBaseId(document.getKnowledgeBaseId());
        task.setDocumentId(document.getId());
        task.setTaskType(type);
        task.setStatus(status);
        task.setAttemptCount(0);
        task.setRecoveryCount(0);
        task.setEnqueuedAt(now());
        return task;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public static String safeError(String error) {
        String value = error == null || error.isBlank() ? "Document task failed" : error.strip();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    public enum RecoveryOutcome {
        UNCHANGED,
        REQUEUED,
        SUCCEEDED,
        FAILED
    }
}
