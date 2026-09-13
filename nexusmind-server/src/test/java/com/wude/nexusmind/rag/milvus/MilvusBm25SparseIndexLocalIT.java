package com.wude.nexusmind.rag.milvus;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusBm25SparseIndexLocalIT {

    @Test
    void rejectsLegacyDenseOnlyCollectionWithExplicitRebuildMessage() {
        String uri = System.getenv().getOrDefault("MILVUS_URI", "http://localhost:19530");
        String collectionName = "test_bm25_legacy_" + System.currentTimeMillis();
        MilvusClientV2 client = client(uri);
        MilvusDenseVectorIndex index = new MilvusDenseVectorIndex(client, properties(uri));
        try {
            CreateCollectionReq.CollectionSchema legacy = CreateCollectionReq.CollectionSchema.builder()
                    .enableDynamicField(false)
                    .build();
            legacy.addField(AddFieldReq.builder().fieldName(MilvusDenseVectorIndex.CHUNK_ID)
                    .dataType(DataType.Int64).isPrimaryKey(true).autoID(false).isNullable(false).build());
            legacy.addField(AddFieldReq.builder().fieldName(MilvusDenseVectorIndex.CONTENT)
                    .dataType(DataType.VarChar).maxLength(8192).isNullable(false).build());
            legacy.addField(AddFieldReq.builder().fieldName(MilvusDenseVectorIndex.EMBEDDING)
                    .dataType(DataType.FloatVector).dimension(4).isNullable(false).build());
            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(legacy)
                    .build());

            assertThatThrownBy(() -> index.ensureExistingCollectionReady(collectionName, 4))
                    .hasMessageContaining("Collection schema upgrade required")
                    .hasMessageContaining("content analyzer");
        } finally {
            dropIfPresent(client, collectionName);
            client.close();
        }
    }

    @Test
    void createsChineseAnalyzerBm25FunctionSparseIndexAndSearchesRawText() {
        String uri = System.getenv().getOrDefault("MILVUS_URI", "http://localhost:19530");
        String collectionName = "test_bm25_" + System.currentTimeMillis();
        MilvusClientV2 client = client(uri);
        MilvusProperties properties = properties(uri);
        MilvusDenseVectorIndex index = new MilvusDenseVectorIndex(client, properties);

        try {
            index.ensureCollectionReady(collectionName, 4);
            DescribeCollectionResp description = client.describeCollection(DescribeCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            var schema = description.getCollectionSchema();
            assertThat(schema.getField(MilvusDenseVectorIndex.CONTENT).getEnableAnalyzer()).isTrue();
            assertThat(schema.getField(MilvusDenseVectorIndex.CONTENT).getAnalyzerParams())
                    .containsEntry("type", "chinese");
            assertThat(schema.getField(MilvusDenseVectorIndex.SPARSE_EMBEDDING).getDataType())
                    .isEqualTo(DataType.SparseFloatVector);
            assertThat(schema.getFunctionList()).singleElement().satisfies(function -> {
                assertThat(function.getName()).isEqualTo("content_bm25");
                assertThat(function.getFunctionType()).isEqualTo(FunctionType.BM25);
                assertThat(function.getInputFieldNames()).containsExactly(MilvusDenseVectorIndex.CONTENT);
                assertThat(function.getOutputFieldNames())
                        .containsExactly(MilvusDenseVectorIndex.SPARSE_EMBEDDING);
            });

            DescribeIndexResp.IndexDesc sparseIndex = client.describeIndex(DescribeIndexReq.builder()
                            .collectionName(collectionName)
                            .fieldName(MilvusDenseVectorIndex.SPARSE_EMBEDDING)
                            .build())
                    .getIndexDescByFieldName(MilvusDenseVectorIndex.SPARSE_EMBEDDING);
            assertThat(sparseIndex.getIndexType()).isEqualTo(IndexParam.IndexType.SPARSE_INVERTED_INDEX);
            assertThat(sparseIndex.getMetricType()).isEqualTo(IndexParam.MetricType.BM25);
            assertThat(sparseIndex.getExtraParams())
                    .containsEntry("inverted_index_algo", "DAAT_MAXSCORE")
                    .containsEntry("bm25_k1", "1.2")
                    .containsEntry("bm25_b", "0.75");

            long seed = System.currentTimeMillis();
            index.upsert(collectionName, List.of(
                    entity(seed, 71L, 701L, "InnoDB 使用等待图检测事务之间的死锁。"),
                    entity(seed + 1, 71L, 702L, "Redis 使用 LRU 或 LFU 进行缓存淘汰。"),
                    entity(seed + 2, 71L, 703L, "MVCC 的 RR 隔离级别通过 Read View 提供一致性读取。")
            ), 4);

            List<Bm25SparseHit> chinese = index.search(collectionName, 71L, "InnoDB 死锁", 3, 4);
            assertThat(chinese).isNotEmpty();
            assertThat(chinese.get(0).chunkId()).isEqualTo(seed);
            assertThat(chinese.get(0).score()).isPositive();

            List<Bm25SparseHit> mixed = index.search(collectionName, 71L, "MVCC RR", 3, 4);
            assertThat(mixed).isNotEmpty();
            assertThat(mixed.get(0).chunkId()).isEqualTo(seed + 2);
        } finally {
            try {
                dropIfPresent(client, collectionName);
            } finally {
                client.close();
            }
        }
    }

    private static MilvusClientV2 client(String uri) {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri(uri)
                .connectTimeoutMs(10_000)
                .rpcDeadlineMs(30_000)
                .build());
    }

    private static MilvusProperties properties(String uri) {
        return new MilvusProperties(
                true,
                uri,
                8192,
                Duration.ofSeconds(30),
                new MilvusProperties.Hnsw(32, 200, 64),
                new MilvusProperties.Bm25("DAAT_MAXSCORE", 1.2, 0.75, "chinese"));
    }

    private static void dropIfPresent(MilvusClientV2 client, String collectionName) {
        if (Boolean.TRUE.equals(client.hasCollection(io.milvus.v2.service.collection.request.HasCollectionReq
                .builder().collectionName(collectionName).build()))) {
            client.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
        }
    }

    private static VectorIndexEntity entity(long chunkId,
                                            long knowledgeBaseId,
                                            long documentId,
                                            String content) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                knowledgeBaseId, documentId, 0, content, null, null, content.length(), null);
        chunk.setId(chunkId);
        return new VectorIndexEntity(chunk, new float[]{1f, 0f, 0f, 0f});
    }
}
