package com.wude.nexusmind.knowledge.task;

import com.wude.nexusmind.knowledge.service.DocumentProcessingService;
import com.wude.nexusmind.rag.index.DocumentIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "nexusmind.document-task.enabled", havingValue = "true")
@ConditionalOnProperty(name = "nexusmind.document-task.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskWorker.class);

    private final DocumentTaskService taskService;
    private final DocumentProcessingService processingService;
    private final DocumentIndexingService indexingService;
    private final DocumentTaskProperties properties;
    private final ThreadPoolTaskExecutor executor;
    // 实例Id
    private final String workerId;
    // DB就是队列，线程池队列容量为0，所以这里用一个AtomicInteger来记录正在执行的任务数
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<Long, Ownership> running = new ConcurrentHashMap<>();

    public DocumentTaskWorker(DocumentTaskService taskService,
                              DocumentProcessingService processingService,
                              DocumentIndexingService indexingService,
                              DocumentTaskProperties properties,
                              @Qualifier("documentTaskExecutor") ThreadPoolTaskExecutor executor,
                              @Qualifier("documentTaskWorkerId") String workerId) {
        this.taskService = taskService;
        this.processingService = processingService;
        this.indexingService = indexingService;
        this.properties = properties;
        this.executor = executor;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${nexusmind.document-task.poll-interval:1s}")
    public void poll() {
        while (reserveSlot()) {
            Optional<KnowledgeDocumentTask> claimed = taskService.claimNext(workerId);
            if (claimed.isEmpty()) {
                inFlight.decrementAndGet();
                return;
            }
            submit(claimed.get());
        }
    }

    @Scheduled(fixedDelayString = "${nexusmind.document-task.heartbeat-interval:10s}")
    public void heartbeat() {
        running.values().forEach(ownership -> {
            if (!taskService.heartbeat(ownership.taskId(), ownership.runToken())) {
                log.warn("Document task heartbeat lost ownership: taskId={}, workerId={}, runToken={}",
                        ownership.taskId(), workerId, abbreviate(ownership.runToken()));
            }
        });
    }

    int inFlightCount() {
        return inFlight.get();
    }

    private boolean reserveSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= properties.getWorkerConcurrency()) return false;
            if (inFlight.compareAndSet(current, current + 1)) return true;
        }
    }

    private void submit(KnowledgeDocumentTask task) {
        try {
            executor.execute(() -> execute(task));
        } catch (RejectedExecutionException exception) {
            inFlight.decrementAndGet();
            log.error("Claimed document task could not be submitted; stale recovery will reclaim it: taskId={}, workerId={}",
                    task.getId(), workerId, exception);
        }
    }

    private void execute(KnowledgeDocumentTask task) {
        long started = System.nanoTime();
        Ownership ownership = new Ownership(task.getId(), task.getRunToken());
        running.put(task.getId(), ownership);
        log.info("Document task started: taskId={}, documentId={}, taskType={}, attempt={}, workerId={}",
                task.getId(), task.getDocumentId(), task.getTaskType(), task.getAttemptCount(), workerId);
        try {
            if (task.getTaskType() == DocumentTaskType.PROCESS) {
                processingService.process(task.getDocumentId());
            } else {
                indexingService.index(task.getDocumentId());
            }
            if (taskService.markSucceeded(task.getId(), task.getRunToken())) {
                log.info("Document task succeeded: taskId={}, documentId={}, taskType={}, attempt={}, durationMs={}, workerId={}",
                        task.getId(), task.getDocumentId(), task.getTaskType(), task.getAttemptCount(),
                        elapsedMillis(started), workerId);
            } else {
                log.warn("Document task success ignored after ownership changed: taskId={}, runToken={}",
                        task.getId(), abbreviate(task.getRunToken()));
            }
        } catch (RuntimeException failure) {
            String safeError = task.getTaskType() + " failed (" + failure.getClass().getSimpleName() + ")";
            boolean updated = taskService.markFailed(task.getId(), task.getRunToken(), safeError);
            log.error("Document task failed: taskId={}, documentId={}, taskType={}, attempt={}, durationMs={}, workerId={}, owned={}",
                    task.getId(), task.getDocumentId(), task.getTaskType(), task.getAttemptCount(),
                    elapsedMillis(started), workerId, updated, failure);
        } finally {
            running.remove(task.getId(), ownership);
            inFlight.decrementAndGet();
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String abbreviate(String token) {
        return token == null ? "none" : token.substring(0, Math.min(token.length(), 8));
    }

    private record Ownership(long taskId, String runToken) {
    }
}
