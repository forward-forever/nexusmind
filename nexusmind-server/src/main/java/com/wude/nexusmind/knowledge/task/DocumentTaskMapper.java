package com.wude.nexusmind.knowledge.task;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface DocumentTaskMapper {
    int insert(KnowledgeDocumentTask task);
    Optional<KnowledgeDocumentTask> findById(long id);
    Optional<KnowledgeDocumentTask> findByIdForUpdate(long id);
    Optional<KnowledgeDocumentTask> findByDocumentAndTypeForUpdate(@Param("documentId") long documentId,
                                                                   @Param("taskType") DocumentTaskType taskType);
    List<KnowledgeDocumentTask> findActiveByKnowledgeBaseId(long knowledgeBaseId);
    Optional<KnowledgeDocumentTask> findNextPendingForUpdate();
    int requeue(@Param("id") long id, @Param("enqueuedAt") LocalDateTime enqueuedAt,
                @Param("resetRecoveryCount") boolean resetRecoveryCount);
    int claim(@Param("id") long id, @Param("workerId") String workerId,
              @Param("runToken") String runToken, @Param("now") LocalDateTime now);
    int heartbeat(@Param("id") long id, @Param("runToken") String runToken,
                  @Param("now") LocalDateTime now);
    int markSucceeded(@Param("id") long id, @Param("runToken") String runToken,
                      @Param("now") LocalDateTime now);
    int markFailed(@Param("id") long id, @Param("runToken") String runToken,
                   @Param("error") String error, @Param("now") LocalDateTime now);
    List<Long> findStaleRunningIds(@Param("cutoff") LocalDateTime cutoff);
    int recoverToPending(@Param("id") long id, @Param("enqueuedAt") LocalDateTime enqueuedAt,
                         @Param("error") String error);
    int reconcileSucceeded(@Param("id") long id, @Param("now") LocalDateTime now);
    int completeLogical(@Param("id") long id, @Param("now") LocalDateTime now);
    int reconcileFailed(@Param("id") long id, @Param("error") String error,
                        @Param("now") LocalDateTime now);
    List<Long> findOrphanProcessingDocumentIds();
    List<Long> findOrphanIndexingDocumentIds();
}
