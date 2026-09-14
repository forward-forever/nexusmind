package com.wude.nexusmind.rag.chat;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.service.DocumentService;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.rag.api.RagStreamEvent;
import com.wude.nexusmind.rag.index.DocumentIndexingService;
import com.wude.nexusmind.rag.milvus.MilvusCollectionNamingStrategy;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=openai",
                "spring.autoconfigure.exclude="
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
                "nexusmind.vector.enabled=true",
                "nexusmind.milvus.enabled=true",
                "nexusmind.rag.enabled=true",
                "nexusmind.ai.embedding.model=deterministic-test",
                "nexusmind.ai.embedding.dimension=4",
                "nexusmind.ai.embedding.batch-size=15"
        }
)
@ActiveProfiles("local")
@Import(RagChatLocalIT.FakeModelConfiguration.class)
class RagChatLocalIT {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentIndexingService indexingService;

    @Autowired
    private RagChatService ragChatService;

    @Autowired
    private MilvusClientV2 milvusClient;

    @Autowired
    private MilvusCollectionNamingStrategy namingStrategy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void retrievesFromRealMilvusEnrichesFromMysqlAndStreamsWithAFakeChatModel() {
        Long knowledgeBaseId = null;
        Long documentId = null;
        try {
            knowledgeBaseId = knowledgeBaseService.create(
                    "rag-local-it-" + UUID.randomUUID(), "MySQL + Milvus + fake chat integration");
            String random = UUID.randomUUID().toString().replace("-", "");
            KnowledgeDocument document = new KnowledgeDocument(
                    knowledgeBaseId,
                    "integration-deadlocks.pdf",
                    null,
                    "application/pdf",
                    100L,
                    random + random);
            documentId = documentService.register(document);
            documentService.markProcessing(documentId);
            documentService.replaceChunksAndMarkReady(documentId, List.of(
                    chunk(knowledgeBaseId, documentId, 0,
                            "InnoDB detects a deadlock wait cycle and rolls back one transaction.", 17),
                    chunk(knowledgeBaseId, documentId, 1,
                            "Banana plants prefer warm weather and regular moisture.", 18),
                    chunk(knowledgeBaseId, documentId, 2,
                            "Java garbage collectors reclaim unreachable heap objects.", 19)));

            KnowledgeDocument indexed = indexingService.index(documentId);
            assertThat(indexed.getIndexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);

            List<RagStreamEvent> events = ragChatService.stream(
                    knowledgeBaseId, "Why does InnoDB roll back a transaction after a deadlock?", 3)
                    .collectList().block();

            assertThat(events).isNotNull();
            assertThat(events).extracting(RagStreamEvent::type)
                    .containsExactly("sources", "delta", "delta", "delta", "done");
            assertThat(events.get(0).sources().get(0).fileName()).isEqualTo("integration-deadlocks.pdf");
            assertThat(events.get(0).sources().get(0).pageNo()).isEqualTo(17);
            assertThat(events.get(0).sources().get(0).id()).isEqualTo("S1");
            assertThat(events.stream().filter(event -> "delta".equals(event.type()))
                    .map(RagStreamEvent::content).reduce("", String::concat)).contains("[S1]");
        } finally {
            if (knowledgeBaseId != null) {
                String collectionName = namingStrategy.forKnowledgeBase(knowledgeBaseId);
                if (Boolean.TRUE.equals(milvusClient.hasCollection(HasCollectionReq.builder()
                        .collectionName(collectionName).build()))) {
                    milvusClient.dropCollection(DropCollectionReq.builder()
                            .collectionName(collectionName).build());
                }
            }
            if (documentId != null) {
                jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
                jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", documentId);
            }
            if (knowledgeBaseId != null) {
                jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
            }
        }
    }

    private static KnowledgeChunk chunk(long knowledgeBaseId,
                                        long documentId,
                                        int index,
                                        String content,
                                        int pageNo) {
        return new KnowledgeChunk(
                knowledgeBaseId, documentId, index, content, pageNo, null, content.length(), null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfiguration {

        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new EmbeddingModel() {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(FakeModelConfiguration::vector).toList();
                }

                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public float[] embed(Document document) {
                    return vector(document.getText());
                }
            };
        }

        @Bean
        ChatAnswerStreamer fakeChatAnswerStreamer() {
            return ignored -> Flux.just("InnoDB ", "rolls back a victim transaction ", "[S1]");
        }

        private static float[] vector(String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.contains("deadlock") || normalized.contains("innodb")) {
                return new float[]{1f, 0f, 0f, 0f};
            }
            if (normalized.contains("banana")) {
                return new float[]{0f, 1f, 0f, 0f};
            }
            return new float[]{0f, 0f, 1f, 0f};
        }
    }
}
