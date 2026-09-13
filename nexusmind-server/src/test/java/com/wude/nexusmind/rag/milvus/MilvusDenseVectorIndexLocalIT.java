package com.wude.nexusmind.rag.milvus;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.rag.exception.VectorIndexException;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MilvusDenseVectorIndexLocalIT {

    @Test
    void createsHnswCollectionUpsertsSearchesAndDeletesByDocument() {
        String uri = System.getenv().getOrDefault("MILVUS_URI", "http://localhost:19530");
        String collectionName = "test_kb_" + System.currentTimeMillis();
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri(uri)
                .connectTimeoutMs(10_000)
                .rpcDeadlineMs(30_000)
                .build());
        MilvusProperties properties = new MilvusProperties(
                true,
                uri,
                8192,
                Duration.ofSeconds(30),
                new MilvusProperties.Hnsw(32, 200, 64),
                new MilvusProperties.Bm25("DAAT_MAXSCORE", 1.2, 0.75, "chinese"));
        MilvusDenseVectorIndex index = new MilvusDenseVectorIndex(client, properties);

        try {
            index.ensureCollectionReady(collectionName, 4);
            DescribeIndexResp.IndexDesc indexDescription = client.describeIndex(DescribeIndexReq.builder()
                            .collectionName(collectionName)
                            .fieldName(MilvusDenseVectorIndex.EMBEDDING)
                            .build())
                    .getIndexDescByFieldName(MilvusDenseVectorIndex.EMBEDDING);
            assertThat(indexDescription.getIndexType()).isEqualTo(IndexParam.IndexType.HNSW);
            assertThat(indexDescription.getMetricType()).isEqualTo(IndexParam.MetricType.COSINE);

            long idSeed = System.currentTimeMillis();
            List<VectorIndexEntity> rows = List.of(
                    entity(idSeed, 41L, 100L, 0, "InnoDB deadlock detection", 12, "Deadlocks",
                            new float[]{1f, 0f, 0f, 0f}),
                    entity(idSeed + 1, 41L, 100L, 1, "Transaction lock cycle", null, null,
                            new float[]{0.9f, 0.1f, 0f, 0f}),
                    entity(idSeed + 2, 41L, 200L, 0, "Unrelated cache topic", null, null,
                            new float[]{0f, 1f, 0f, 0f})
            );
            index.upsert(collectionName, rows, 4);

            List<DenseVectorHit> beforeDelete = index.search(
                    collectionName, 41L, new float[]{1f, 0f, 0f, 0f}, 3, 4);
            assertThat(beforeDelete).hasSize(3);
            assertThat(beforeDelete).extracting(DenseVectorHit::chunkId)
                    .containsExactly(idSeed, idSeed + 1, idSeed + 2);
            assertThat(beforeDelete.get(0).chunkId()).isEqualTo(idSeed);
            assertThat(beforeDelete.get(0).documentId()).isEqualTo(100L);
            assertThat(beforeDelete.get(0).pageNo()).isEqualTo(12);
            assertThat(beforeDelete.get(0).sectionTitle()).isEqualTo("Deadlocks");
            assertThat(beforeDelete.get(0).score()).isCloseTo(1.0f, within(0.0001f));
            assertThat(beforeDelete.get(1).score()).isCloseTo(0.9938837f, within(0.0001f));
            assertThat(beforeDelete.get(2).score()).isCloseTo(0.0f, within(0.0001f));
            assertThat(beforeDelete).extracting(DenseVectorHit::score).isSortedAccordingTo(java.util.Comparator.reverseOrder());

            assertThatThrownBy(() -> index.ensureExistingCollectionReady(collectionName, 5))
                    .isInstanceOf(VectorIndexException.class)
                    .hasMessageContaining("dimension")
                    .hasMessageContaining("expected 5");

            index.deleteByDocumentId(collectionName, 100L);
            List<DenseVectorHit> afterDelete = index.search(
                    collectionName, 41L, new float[]{1f, 0f, 0f, 0f}, 3, 4);
            assertThat(afterDelete).extracting(DenseVectorHit::documentId).containsExactly(200L);
        } finally {
            try {
                client.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
            } finally {
                client.close();
            }
        }
    }

    private static VectorIndexEntity entity(long chunkId,
                                            long knowledgeBaseId,
                                            long documentId,
                                            int chunkIndex,
                                            String content,
                                            Integer pageNo,
                                            String sectionTitle,
                                            float[] vector) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                knowledgeBaseId,
                documentId,
                chunkIndex,
                content,
                pageNo,
                sectionTitle,
                content.length(),
                null);
        chunk.setId(chunkId);
        return new VectorIndexEntity(chunk, vector);
    }
}
