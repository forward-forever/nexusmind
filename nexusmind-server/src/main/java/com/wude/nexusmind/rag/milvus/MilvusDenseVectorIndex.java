package com.wude.nexusmind.rag.milvus;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.rag.exception.VectorIndexException;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MilvusDenseVectorIndex implements DenseVectorIndex {

    public static final String CHUNK_ID = "chunk_id";
    public static final String KNOWLEDGE_BASE_ID = "knowledge_base_id";
    public static final String DOCUMENT_ID = "document_id";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String CONTENT = "content";
    public static final String PAGE_NO = "page_no";
    public static final String SECTION_TITLE = "section_title";
    public static final String EMBEDDING = "embedding";

    private static final String EMBEDDING_INDEX = "embedding_hnsw";
    private static final int SECTION_TITLE_MAX_LENGTH = 2048;
    private static final List<String> SEARCH_OUTPUT_FIELDS = List.of(
            CHUNK_ID, DOCUMENT_ID, CHUNK_INDEX, CONTENT, PAGE_NO, SECTION_TITLE);

    private final MilvusClientV2 client;
    private final MilvusProperties properties;

    public MilvusDenseVectorIndex(MilvusClientV2 client, MilvusProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void ensureCollectionReady(String collectionName, int dimension) {
        requireCollectionNameAndDimension(collectionName, dimension);
        try {
            if (!hasCollection(collectionName)) {
                createCollection(collectionName, dimension);
            }
            validateCollection(collectionName, dimension);
            ensureHnswIndex(collectionName);
            ensureLoaded(collectionName);
        } catch (VectorIndexException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VectorIndexException("Failed to prepare Milvus collection " + collectionName, exception);
        }
    }

    @Override
    public void ensureExistingCollectionReady(String collectionName, int dimension) {
        requireCollectionNameAndDimension(collectionName, dimension);
        try {
            if (!hasCollection(collectionName)) {
                throw new VectorIndexException("Milvus collection does not exist: " + collectionName);
            }
            validateCollection(collectionName, dimension);
            validateHnswIndex(collectionName);
            ensureLoaded(collectionName);
        } catch (VectorIndexException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VectorIndexException("Failed to validate Milvus collection " + collectionName, exception);
        }
    }

    @Override
    public void upsert(String collectionName, List<VectorIndexEntity> entities, int dimension) {
        if (entities == null || entities.isEmpty()) {
            throw new IllegalArgumentException("Milvus upsert batch cannot be empty");
        }
        List<JsonObject> rows = entities.stream()
                .map(entity -> toMilvusRow(entity, dimension))
                .toList();
        try {
            UpsertResp response = client.upsert(UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(rows)
                    .build());
            if (response.getUpsertCnt() != rows.size()) {
                throw new VectorIndexException(
                        "Milvus upsert count %d does not match requested count %d"
                                .formatted(response.getUpsertCnt(), rows.size()));
            }
        } catch (VectorIndexException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VectorIndexException(
                    "Milvus upsert failed for collection %s (batchSize=%d)"
                            .formatted(collectionName, rows.size()),
                    exception);
        }
    }

    @Override
    public void deleteByDocumentId(String collectionName, long documentId) {
        try {
            if (!hasCollection(collectionName)) {
                return;
            }
            client.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(DOCUMENT_ID + " == " + documentId)
                    .build());
        } catch (RuntimeException exception) {
            throw new VectorIndexException(
                    "Failed to delete document %d from Milvus collection %s"
                            .formatted(documentId, collectionName),
                    exception);
        }
    }

    @Override
    public List<DenseVectorHit> search(String collectionName,
                                       long knowledgeBaseId,
                                       float[] queryVector,
                                       int topK,
                                       int dimension) {
        validateVector(queryVector, dimension, "query");
        ensureExistingCollectionReady(collectionName, dimension);
        try {
            SearchResp response = client.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField(EMBEDDING)
                    .metricType(IndexParam.MetricType.COSINE)
                    .topK(topK)
                    .filter(KNOWLEDGE_BASE_ID + " == " + knowledgeBaseId)
                    .outputFields(SEARCH_OUTPUT_FIELDS)
                    .data(List.of(new FloatVec(queryVector)))
                    .searchParams(Map.of("ef", properties.hnsw().ef()))
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
            if (response.getSearchResults().isEmpty()) {
                return List.of();
            }
            return response.getSearchResults().get(0).stream()
                    .map(this::toHit)
                    .sorted(Comparator.comparingDouble(DenseVectorHit::score).reversed())
                    .toList();
        } catch (RuntimeException exception) {
            throw new VectorIndexException("Dense search failed for collection " + collectionName, exception);
        }
    }

    private boolean hasCollection(String collectionName) {
        return Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build()));
    }

    private void createCollection(String collectionName, int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .build();
        schema.addField(field(CHUNK_ID, DataType.Int64, false, null, null, true, false));
        schema.addField(field(KNOWLEDGE_BASE_ID, DataType.Int64, false, null, null, false, false));
        schema.addField(field(DOCUMENT_ID, DataType.Int64, false, null, null, false, false));
        schema.addField(field(CHUNK_INDEX, DataType.Int32, false, null, null, false, false));
        schema.addField(field(CONTENT, DataType.VarChar, false, properties.contentMaxLength(), null, false, false));
        schema.addField(field(PAGE_NO, DataType.Int32, true, null, null, false, false));
        schema.addField(field(SECTION_TITLE, DataType.VarChar, true, SECTION_TITLE_MAX_LENGTH, null, false, false));
        schema.addField(field(EMBEDDING, DataType.FloatVector, false, null, dimension, false, false));

        try {
            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .description("NexusMind dense retrieval projection")
                    .collectionSchema(schema)
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
        } catch (RuntimeException createFailure) {
            if (!hasCollection(collectionName)) {
                throw createFailure;
            }
        }
    }

    private AddFieldReq field(String name,
                              DataType type,
                              boolean nullable,
                              Integer maxLength,
                              Integer dimension,
                              boolean primary,
                              boolean autoId) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(type)
                .isNullable(nullable)
                .maxLength(maxLength)
                .dimension(dimension)
                .isPrimaryKey(primary)
                .autoID(autoId)
                .build();
    }

    private void validateCollection(String collectionName, int expectedDimension) {
        DescribeCollectionResp description = client.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        CreateCollectionReq.CollectionSchema schema = description.getCollectionSchema();
        if (schema == null) {
            throw schemaMismatch(collectionName, "schema is missing");
        }
        CreateCollectionReq.FieldSchema primary = schema.getField(CHUNK_ID);
        if (primary == null
                || primary.getDataType() != DataType.Int64
                || !Boolean.TRUE.equals(primary.getIsPrimaryKey())
                || Boolean.TRUE.equals(primary.getAutoID())) {
            throw schemaMismatch(collectionName, "chunk_id must be a non-auto Int64 primary key");
        }
        CreateCollectionReq.FieldSchema embedding = schema.getField(EMBEDDING);
        if (embedding == null || embedding.getDataType() != DataType.FloatVector) {
            throw schemaMismatch(collectionName, "embedding must be a FloatVector field");
        }
        if (!Integer.valueOf(expectedDimension).equals(embedding.getDimension())) {
            throw schemaMismatch(collectionName,
                    "embedding dimension %s does not match expected %d"
                            .formatted(embedding.getDimension(), expectedDimension));
        }
        if (!CHUNK_ID.equals(description.getPrimaryFieldName())) {
            throw schemaMismatch(collectionName, "primary field is not chunk_id");
        }
        if (Boolean.TRUE.equals(description.getEnableDynamicField())) {
            throw schemaMismatch(collectionName, "dynamic fields must be disabled");
        }
    }

    private void ensureHnswIndex(String collectionName) {
        List<String> indexes = client.listIndexes(io.milvus.v2.service.index.request.ListIndexesReq.builder()
                .collectionName(collectionName)
                .fieldName(EMBEDDING)
                .build());
        if (indexes.isEmpty()) {
            IndexParam index = IndexParam.builder()
                    .fieldName(EMBEDDING)
                    .indexName(EMBEDDING_INDEX)
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of(
                            "M", properties.hnsw().m(),
                            "efConstruction", properties.hnsw().efConstruction()))
                    .build();
            try {
                client.createIndex(CreateIndexReq.builder()
                        .collectionName(collectionName)
                        .indexParams(List.of(index))
                        .sync(true)
                        .timeout(properties.readyTimeout().toMillis())
                        .build());
            } catch (RuntimeException createFailure) {
                List<String> concurrentIndexes = client.listIndexes(
                        io.milvus.v2.service.index.request.ListIndexesReq.builder()
                                .collectionName(collectionName)
                                .fieldName(EMBEDDING)
                                .build());
                if (concurrentIndexes.isEmpty()) {
                    throw createFailure;
                }
            }
        }
        validateHnswIndex(collectionName);
    }

    private void validateHnswIndex(String collectionName) {
        DescribeIndexResp response = client.describeIndex(DescribeIndexReq.builder()
                .collectionName(collectionName)
                .fieldName(EMBEDDING)
                .build());
        DescribeIndexResp.IndexDesc index = response.getIndexDescByFieldName(EMBEDDING);
        if (index == null
                || index.getIndexType() != IndexParam.IndexType.HNSW
                || index.getMetricType() != IndexParam.MetricType.COSINE) {
            throw schemaMismatch(collectionName, "embedding index must be HNSW with COSINE metric");
        }
    }

    private void ensureLoaded(String collectionName) {
        GetLoadStateReq stateRequest = GetLoadStateReq.builder().collectionName(collectionName).build();
        if (!Boolean.TRUE.equals(client.getLoadState(stateRequest))) {
            client.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .sync(true)
                    .timeout(properties.readyTimeout().toMillis())
                    .build());
        }
        long deadline = System.nanoTime() + properties.readyTimeout().toNanos();
        while (!Boolean.TRUE.equals(client.getLoadState(stateRequest))) {
            if (System.nanoTime() >= deadline) {
                throw new VectorIndexException("Timed out waiting for Milvus collection to load: " + collectionName);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new VectorIndexException("Interrupted while waiting for Milvus collection: " + collectionName,
                        exception);
            }
        }
    }

    private JsonObject toMilvusRow(VectorIndexEntity entity, int dimension) {
        KnowledgeChunk chunk = entity.chunk();
        validateVector(entity.embedding(), dimension, "chunk " + chunk.getId());
        requireUtf8Length(chunk.getContent(), properties.contentMaxLength(), "Chunk content");
        if (chunk.getSectionTitle() != null) {
            requireUtf8Length(chunk.getSectionTitle(), SECTION_TITLE_MAX_LENGTH, "Section title");
        }
        if (chunk.getId() == null) {
            throw new IllegalArgumentException("Persisted chunk ID is required for Milvus upsert");
        }

        JsonObject row = new JsonObject();
        row.addProperty(CHUNK_ID, chunk.getId());
        row.addProperty(KNOWLEDGE_BASE_ID, chunk.getKnowledgeBaseId());
        row.addProperty(DOCUMENT_ID, chunk.getDocumentId());
        row.addProperty(CHUNK_INDEX, chunk.getChunkIndex());
        row.addProperty(CONTENT, chunk.getContent());
        addNullable(row, PAGE_NO, chunk.getPageNo());
        addNullable(row, SECTION_TITLE, chunk.getSectionTitle());
        JsonArray vector = new JsonArray(entity.embedding().length);
        for (float value : entity.embedding()) {
            if (!Float.isFinite(value)) {
                throw new VectorIndexException("Embedding contains a non-finite value for chunk " + chunk.getId());
            }
            vector.add(value);
        }
        row.add(EMBEDDING, vector);
        return row;
    }

    private DenseVectorHit toHit(SearchResp.SearchResult result) {
        Map<String, Object> entity = result.getEntity();
        return new DenseVectorHit(
                number(result.getId() != null ? result.getId() : entity.get(CHUNK_ID), CHUNK_ID).longValue(),
                number(entity.get(DOCUMENT_ID), DOCUMENT_ID).longValue(),
                number(entity.get(CHUNK_INDEX), CHUNK_INDEX).intValue(),
                result.getScore(),
                Objects.toString(entity.get(CONTENT), ""),
                nullableNumber(entity.get(PAGE_NO)),
                entity.get(SECTION_TITLE) == null ? null : entity.get(SECTION_TITLE).toString()
        );
    }

    private static Number number(Object value, String field) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new VectorIndexException("Milvus result field is not numeric: " + field, exception);
        }
    }

    private static Integer nullableNumber(Object value) {
        return value == null ? null : number(value, PAGE_NO).intValue();
    }

    private static void addNullable(JsonObject row, String field, Number value) {
        if (value == null) {
            row.add(field, JsonNull.INSTANCE);
        } else {
            row.addProperty(field, value);
        }
    }

    private static void addNullable(JsonObject row, String field, String value) {
        if (value == null) {
            row.add(field, JsonNull.INSTANCE);
        } else {
            row.addProperty(field, value);
        }
    }

    private static void validateVector(float[] vector, int dimension, String label) {
        if (vector == null || vector.length != dimension) {
            throw new VectorIndexException(
                    "%s vector dimension is %d, expected %d"
                            .formatted(label, vector == null ? 0 : vector.length, dimension));
        }
    }

    private static void requireUtf8Length(String value, int maximumBytes, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        int actualBytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes > maximumBytes) {
            throw new VectorIndexException(
                    "%s UTF-8 length %d exceeds Milvus max length %d"
                            .formatted(field, actualBytes, maximumBytes));
        }
    }

    private static void requireCollectionNameAndDimension(String collectionName, int dimension) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("Collection name is required");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("Vector dimension must be positive");
        }
    }

    private static VectorIndexException schemaMismatch(String collectionName, String detail) {
        return new VectorIndexException("Milvus collection schema mismatch for %s: %s"
                .formatted(collectionName, detail));
    }
}
