package com.wude.nexusmind.knowledge.task;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.ChunkService;
import com.wude.nexusmind.knowledge.service.DocumentIngestionService;
import com.wude.nexusmind.knowledge.service.DocumentIndexStateService;
import com.wude.nexusmind.knowledge.service.DocumentProcessingService;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.model.config.EmbeddingProperties;
import com.wude.nexusmind.rag.embedding.EmbeddingBatchService;
import com.wude.nexusmind.rag.index.DocumentIndexingService;
import com.wude.nexusmind.rag.index.EmbeddingBatchPlanner;
import com.wude.nexusmind.rag.milvus.DenseVectorHit;
import com.wude.nexusmind.rag.milvus.DenseVectorIndex;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import com.wude.nexusmind.rag.milvus.VectorIndexEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "nexusmind.document-task.enabled=true",
                "nexusmind.document-task.worker-enabled=false",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "nexusmind.agent.enabled=false",
                "nexusmind.vector.enabled=false",
                "nexusmind.milvus.enabled=false",
                "nexusmind.rag.enabled=false"
        })
@ActiveProfiles("local")
class DocumentTaskLocalIT {

    private static final Path STORAGE_ROOT = createTempDirectory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nexusmind.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private DocumentTaskService taskService;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentIngestionService ingestionService;
    @Autowired private DocumentProcessingService processingService;
    @Autowired private DocumentIndexStateService indexStateService;
    @Autowired private ChunkService chunkService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void durableQueueSupportsConcurrentEnqueueClaimFencingRecoveryAndIdempotentProcessing() throws Exception {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("4");
        long knowledgeBaseId = knowledgeBaseService.create("task-it-" + System.nanoTime(), "CP16 LocalIT");
        try {
            long documentId = register(knowledgeBaseId, "enqueue.txt");

            List<Long> enqueueIds = concurrentEnqueue(documentId);
            assertThat(enqueueIds).containsOnly(enqueueIds.get(0));
            assertThat(countTasks(documentId, DocumentTaskType.PROCESS)).isEqualTo(1);

            List<Optional<KnowledgeDocumentTask>> claims = concurrentClaim();
            List<KnowledgeDocumentTask> owned = claims.stream().flatMap(Optional::stream)
                    .filter(task -> task.getDocumentId() == documentId).toList();
            assertThat(owned).hasSize(1);
            KnowledgeDocumentTask claimed = owned.get(0);
            assertThat(taskService.markSucceeded(claimed.getId(), "wrong-token")).isFalse();
            assertThat(taskService.markSucceeded(claimed.getId(), claimed.getRunToken())).isTrue();
            assertThat(taskService.get(claimed.getId()).getStatus()).isEqualTo(DocumentTaskStatus.SUCCEEDED);

            verifyStaleRestartRecovery(knowledgeBaseId);
            verifyBusinessSuccessReconciliation(knowledgeBaseId);
            verifyOrphanRecovery(knowledgeBaseId);
            verifyProcessReexecutionConverges(knowledgeBaseId);
            verifyIndexReexecutionConverges(knowledgeBaseId);
        } finally {
            jdbc.update("DELETE FROM knowledge_document_task WHERE knowledge_base_id=?", knowledgeBaseId);
            jdbc.update("DELETE FROM knowledge_chunk WHERE knowledge_base_id=?", knowledgeBaseId);
            jdbc.update("DELETE FROM knowledge_document WHERE knowledge_base_id=?", knowledgeBaseId);
            jdbc.update("DELETE FROM knowledge_base WHERE id=?", knowledgeBaseId);
        }
    }

    private List<Long> concurrentEnqueue(long documentId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return taskService.enqueueProcess(documentId).task().getId();
                }));
            }
            start.countDown();
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Optional<KnowledgeDocumentTask>> concurrentClaim() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<KnowledgeDocumentTask>> first = pool.submit(() -> {
                start.await();
                return taskService.claimNext("worker-A");
            });
            Future<Optional<KnowledgeDocumentTask>> second = pool.submit(() -> {
                start.await();
                return taskService.claimNext("worker-B");
            });
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private void verifyStaleRestartRecovery(long knowledgeBaseId) {
        long documentId = register(knowledgeBaseId, "stale.txt");
        KnowledgeDocumentTask task = taskService.enqueueProcess(documentId).task();
        KnowledgeDocumentTask claimed = taskService.claimNext("crashed-worker").orElseThrow();
        assertThat(claimed.getId()).isEqualTo(task.getId());
        jdbc.update("UPDATE knowledge_document SET status='PROCESSING' WHERE id=?", documentId);
        makeStale(task.getId());

        assertThat(taskService.reconcileStale(task.getId()))
                .isEqualTo(DocumentTaskService.RecoveryOutcome.REQUEUED);
        assertThat(documentService.get(documentId).getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(taskService.get(task.getId()).getStatus()).isEqualTo(DocumentTaskStatus.PENDING);
        jdbc.update("UPDATE knowledge_document_task SET status='FAILED', last_error='test cleanup' WHERE id=?", task.getId());
    }

    private void verifyBusinessSuccessReconciliation(long knowledgeBaseId) {
        long documentId = register(knowledgeBaseId, "completed.txt");
        KnowledgeDocumentTask task = taskService.enqueueProcess(documentId).task();
        KnowledgeDocumentTask claimed = taskService.claimNext("crashed-after-commit").orElseThrow();
        assertThat(claimed.getId()).isEqualTo(task.getId());
        jdbc.update("UPDATE knowledge_document SET status='READY' WHERE id=?", documentId);
        makeStale(task.getId());

        assertThat(taskService.reconcileStale(task.getId()))
                .isEqualTo(DocumentTaskService.RecoveryOutcome.SUCCEEDED);
        assertThat(taskService.get(task.getId()).getStatus()).isEqualTo(DocumentTaskStatus.SUCCEEDED);
    }

    private void verifyOrphanRecovery(long knowledgeBaseId) {
        long documentId = register(knowledgeBaseId, "orphan.txt");
        jdbc.update("UPDATE knowledge_document SET status='PROCESSING' WHERE id=?", documentId);

        assertThat(taskService.recoverOrphanProcess(documentId)).isTrue();
        assertThat(documentService.get(documentId).getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(taskService.listActive(knowledgeBaseId)).anySatisfy(task -> {
            assertThat(task.getDocumentId()).isEqualTo(documentId);
            assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.PENDING);
        });
        jdbc.update("UPDATE knowledge_document_task SET status='FAILED', last_error='test cleanup' WHERE document_id=?", documentId);
    }

    private void verifyProcessReexecutionConverges(long knowledgeBaseId) {
        byte[] content = "MVCC keeps multiple row versions. Read View decides visibility."
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "idempotent.txt", "text/plain", content);
        long documentId = ingestionService.upload(knowledgeBaseId, file).document().getId();
        processingService.process(documentId);
        List<String> first = chunkService.listByDocument(documentId).stream()
                .map(chunk -> chunk.getChunkIndex() + ":" + chunk.getContent()).toList();

        jdbc.update("UPDATE knowledge_document SET status='UPLOADED' WHERE id=?", documentId);
        processingService.process(documentId);
        List<KnowledgeChunk> secondChunks = chunkService.listByDocument(documentId);
        List<String> second = secondChunks.stream()
                .map(chunk -> chunk.getChunkIndex() + ":" + chunk.getContent()).toList();

        assertThat(second).containsExactlyElementsOf(first);
        assertThat(documentService.get(documentId).getChunkCount()).isEqualTo(secondChunks.size());
    }

    private void verifyIndexReexecutionConverges(long knowledgeBaseId) {
        byte[] content = "Stable chunk identity makes vector upsert idempotent."
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "idempotent-index.txt", "text/plain", content);
        long documentId = ingestionService.upload(knowledgeBaseId, file).document().getId();
        processingService.process(documentId);

        int dimension = knowledgeBaseService.get(knowledgeBaseId).getEmbeddingDimension();
        EmbeddingProperties embeddingProperties = new EmbeddingProperties(
                "qwen3.7-text-embedding-flash", dimension, 15, 7_500);
        RecordingVectorIndex vectorIndex = new RecordingVectorIndex();
        DocumentIndexingService indexingService = new DocumentIndexingService(
                documentService,
                indexStateService,
                knowledgeBaseService,
                chunkService,
                new EmbeddingBatchService(new FakeEmbeddingModel(dimension), embeddingProperties),
                new EmbeddingBatchPlanner(embeddingProperties),
                vectorIndex,
                new MilvusCollectionNamingStrategy());

        indexingService.index(documentId);
        List<Long> firstProjection = vectorIndex.lastUpsertedChunkIds();
        jdbc.update("UPDATE knowledge_document SET index_status='NOT_INDEXED', indexed_at=NULL WHERE id=?", documentId);
        indexingService.index(documentId);

        assertThat(vectorIndex.lastUpsertedChunkIds()).containsExactlyElementsOf(firstProjection);
        assertThat(documentService.get(documentId).getIndexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
    }

    private long register(long knowledgeBaseId, String fileName) {
        String hash = String.format("%064x", Math.abs((fileName + System.nanoTime()).hashCode()));
        KnowledgeDocument document = new KnowledgeDocument(knowledgeBaseId, fileName,
                "not-used/" + fileName, "text/plain", 1L, hash);
        return documentService.register(document);
    }

    private int countTasks(long documentId, DocumentTaskType type) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document_task WHERE document_id=? AND task_type=?",
                Integer.class, documentId, type.name());
        return count == null ? 0 : count;
    }

    private void makeStale(long taskId) {
        jdbc.update("UPDATE knowledge_document_task SET heartbeat_at=? WHERE id=?",
                LocalDateTime.now(Clock.systemUTC()).minusMinutes(10), taskId);
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("nexusmind-task-it-");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {

        private final int dimension;

        private FakeEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(ignored -> new float[dimension]).toList();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingVectorIndex implements DenseVectorIndex {

        private List<Long> lastUpsertedChunkIds = List.of();

        @Override
        public void ensureCollectionReady(String collectionName, int dimension) {
        }

        @Override
        public void ensureExistingCollectionReady(String collectionName, int dimension) {
        }

        @Override
        public void upsert(String collectionName, List<VectorIndexEntity> entities, int dimension) {
            lastUpsertedChunkIds = entities.stream().map(entity -> entity.chunk().getId()).toList();
        }

        @Override
        public void deleteByDocumentId(String collectionName, long documentId) {
        }

        @Override
        public List<DenseVectorHit> search(String collectionName, long knowledgeBaseId,
                                           float[] queryVector, int topK, int dimension) {
            return List.of();
        }

        private List<Long> lastUpsertedChunkIds() {
            return lastUpsertedChunkIds;
        }
    }
}
