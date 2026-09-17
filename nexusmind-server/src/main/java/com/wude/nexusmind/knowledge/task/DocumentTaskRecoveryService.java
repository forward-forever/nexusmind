package com.wude.nexusmind.knowledge.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "nexusmind.document-task.enabled", havingValue = "true")
@ConditionalOnProperty(name = "nexusmind.document-task.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentTaskRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskRecoveryService.class);
    private final DocumentTaskService taskService;

    public DocumentTaskRecoveryService(DocumentTaskService taskService) {
        this.taskService = taskService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${nexusmind.document-task.recovery-interval:30s}")
    public void scheduledRecovery() {
        reconcile();
    }

    /**
     * Recover tasks that were not completed due to worker failure.
     *   ① 僵尸 RUNNING 任务（心跳断掉的 stale task）
     *   ② 流失的 PROCESSING 文档（没有对应任务的 orphan document）
     *   ③ 流失的 INDEXING 文档（没有对应任务的 orphan document）
     */
    public void reconcile() {
        taskService.findStaleTaskIds().forEach(taskId -> {
            DocumentTaskService.RecoveryOutcome outcome = taskService.reconcileStale(taskId);
            if (outcome != DocumentTaskService.RecoveryOutcome.UNCHANGED) {
                log.info("Document task recovered: taskId={}, outcome={}", taskId, outcome);
            }
        });
        taskService.findOrphanProcessingDocumentIds().forEach(documentId -> {
            if (taskService.recoverOrphanProcess(documentId)) {
                log.warn("Recovered orphan PROCESSING document: documentId={}", documentId);
            }
        });
        taskService.findOrphanIndexingDocumentIds().forEach(documentId -> {
            if (taskService.recoverOrphanIndex(documentId)) {
                log.warn("Recovered orphan INDEXING document: documentId={}", documentId);
            }
        });
    }
}
