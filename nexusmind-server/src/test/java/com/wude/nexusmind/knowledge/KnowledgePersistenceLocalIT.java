package com.wude.nexusmind.knowledge;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.mapper.KnowledgeBaseMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.DocumentIndexStateService;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentIndexStateException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "mybatis.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "nexusmind.vector.enabled=false",
                "nexusmind.milvus.enabled=false",
                "nexusmind.rag.enabled=false"
        }
)
@ActiveProfiles("local")
class KnowledgePersistenceLocalIT {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private KnowledgeDocumentMapper documentMapper;

    @Autowired
    private KnowledgeChunkMapper chunkMapper;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentIndexStateService documentIndexStateService;

    @Test
    void mapperCrudBatchInsertAndTransactionRollbackWork() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("2");

        Long knowledgeBaseId = null;
        Long documentId = null;
        try {
            KnowledgeBase knowledgeBase = new KnowledgeBase(
                    "checkpoint-2-smoke",
                    "Local MySQL persistence smoke test",
                    "text-embedding-smoke",
                    1536,
                    KnowledgeBaseStatus.ACTIVE
            );
            assertThat(knowledgeBaseMapper.insert(knowledgeBase)).isEqualTo(1);
            knowledgeBaseId = knowledgeBase.getId();
            assertThat(knowledgeBaseId).isNotNull();
            assertThat(knowledgeBaseMapper.findById(knowledgeBaseId)).isPresent();
            assertThat(knowledgeBaseMapper.findAll())
                    .extracting(KnowledgeBase::getId)
                    .contains(knowledgeBaseId);

            knowledgeBase.setName("checkpoint-2-smoke-updated");
            knowledgeBase.setDescription("Updated by mapper smoke test");
            assertThat(knowledgeBaseMapper.update(knowledgeBase)).isEqualTo(1);
            assertThat(knowledgeBaseMapper.findById(knowledgeBaseId).orElseThrow().getName())
                    .isEqualTo("checkpoint-2-smoke-updated");

            KnowledgeDocument document = new KnowledgeDocument(
                    knowledgeBaseId,
                    "checkpoint-2.txt",
                    null,
                    "text/plain",
                    42L,
                    "a".repeat(64)
            );
            assertThat(documentMapper.insert(document)).isEqualTo(1);
            documentId = document.getId();
            assertThat(documentId).isNotNull();
            assertThat(documentMapper.findById(documentId)).isPresent();
            assertThat(documentMapper.findById(documentId).orElseThrow().getIndexStatus())
                    .isEqualTo(DocumentIndexStatus.NOT_INDEXED);
            assertThat(documentMapper.findByKnowledgeBaseId(knowledgeBaseId))
                    .extracting(KnowledgeDocument::getId)
                    .contains(documentId);
            assertThat(documentMapper.findByIds(List.of(documentId)))
                    .extracting(KnowledgeDocument::getId)
                    .containsExactly(documentId);

            assertThat(documentMapper.updateStatus(documentId, DocumentStatus.PROCESSING, 0, null))
                    .isEqualTo(1);
            assertThat(documentMapper.findById(documentId).orElseThrow().getStatus())
                    .isEqualTo(DocumentStatus.PROCESSING);

            List<KnowledgeChunk> initialChunks = List.of(
                    chunk(knowledgeBaseId, documentId, 0, "first persisted chunk"),
                    chunk(knowledgeBaseId, documentId, 1, "second persisted chunk")
            );
            assertThat(chunkMapper.batchInsert(initialChunks)).isEqualTo(2);
            assertThat(initialChunks).allSatisfy(chunk -> assertThat(chunk.getId()).isNotNull());
            assertThat(chunkMapper.findByDocumentId(documentId))
                    .extracting(KnowledgeChunk::getChunkIndex)
                    .containsExactly(0, 1);

            assertThat(chunkMapper.deleteByDocumentId(documentId)).isEqualTo(2);
            assertThat(chunkMapper.findByDocumentId(documentId)).isEmpty();

            List<KnowledgeChunk> largeReplacement = new ArrayList<>(1_201);
            for (int index = 0; index < 1_201; index++) {
                largeReplacement.add(chunk(
                        knowledgeBaseId, documentId, index, "large replacement " + index));
            }
            documentService.replaceChunksAndMarkReady(documentId, largeReplacement);
            assertThat(largeReplacement).allSatisfy(chunk -> assertThat(chunk.getId()).isNotNull());
            assertThat(chunkMapper.findByDocumentId(documentId)).hasSize(1_201);
            assertThat(documentMapper.findById(documentId).orElseThrow().getChunkCount()).isEqualTo(1_201);

            assertThat(chunkMapper.deleteByDocumentId(documentId)).isEqualTo(1_201);

            List<KnowledgeChunk> rollbackBaseline = List.of(
                    chunk(knowledgeBaseId, documentId, 0, "rollback baseline zero"),
                    chunk(knowledgeBaseId, documentId, 1, "rollback baseline one")
            );
            assertThat(chunkMapper.batchInsert(rollbackBaseline)).isEqualTo(2);
            assertThat(documentMapper.updateStatus(documentId, DocumentStatus.PROCESSING, 2, null))
                    .isEqualTo(1);

            long persistedDocumentId = documentId;
            List<KnowledgeChunk> duplicateIndexes = new ArrayList<>(1_201);
            for (int index = 0; index < 1_201; index++) {
                duplicateIndexes.add(chunk(
                        knowledgeBaseId, documentId, index, "rollback candidate " + index));
            }
            duplicateIndexes.set(700, chunk(
                    knowledgeBaseId, documentId, 0, "duplicate index in second batch"));
            assertThatThrownBy(() -> documentService.replaceChunksAndMarkReady(persistedDocumentId, duplicateIndexes))
                    .isInstanceOf(DataAccessException.class);

            assertThat(chunkMapper.findByDocumentId(documentId))
                    .extracting(KnowledgeChunk::getContent)
                    .containsExactly("rollback baseline zero", "rollback baseline one");
            KnowledgeDocument rolledBackDocument = documentMapper.findById(documentId).orElseThrow();
            assertThat(rolledBackDocument.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
            assertThat(rolledBackDocument.getChunkCount()).isEqualTo(2);

            assertThat(documentMapper.updateStatus(documentId, DocumentStatus.READY, 2, null)).isEqualTo(1);
            documentIndexStateService.markIndexing(documentId);
            assertThat(documentMapper.findById(documentId).orElseThrow().getIndexStatus())
                    .isEqualTo(DocumentIndexStatus.INDEXING);
            assertThatThrownBy(() -> documentIndexStateService.markIndexing(persistedDocumentId))
                    .isInstanceOf(InvalidDocumentIndexStateException.class);

            documentIndexStateService.markFailed(documentId, "simulated index failure");
            KnowledgeDocument failedIndex = documentMapper.findById(documentId).orElseThrow();
            assertThat(failedIndex.getStatus()).isEqualTo(DocumentStatus.READY);
            assertThat(failedIndex.getIndexStatus()).isEqualTo(DocumentIndexStatus.FAILED);
            assertThat(failedIndex.getIndexErrorMessage()).isEqualTo("simulated index failure");
            assertThat(failedIndex.getIndexedAt()).isNull();

            documentIndexStateService.markIndexing(documentId);
            documentIndexStateService.markIndexed(documentId);
            KnowledgeDocument indexed = documentMapper.findById(documentId).orElseThrow();
            assertThat(indexed.getStatus()).isEqualTo(DocumentStatus.READY);
            assertThat(indexed.getIndexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
            assertThat(indexed.getIndexErrorMessage()).isNull();
            assertThat(indexed.getIndexedAt()).isNotNull();
            assertThatThrownBy(() -> documentIndexStateService.markIndexing(persistedDocumentId))
                    .isInstanceOf(InvalidDocumentIndexStateException.class);
        } finally {
            if (documentId != null) {
                jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
                jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", documentId);
            }
            if (knowledgeBaseId != null) {
                jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
            }
        }
    }

    private static KnowledgeChunk chunk(long knowledgeBaseId, long documentId, int chunkIndex, String content) {
        return new KnowledgeChunk(
                knowledgeBaseId,
                documentId,
                chunkIndex,
                content,
                chunkIndex + 1,
                "Smoke section",
                content.length(),
                null
        );
    }
}
