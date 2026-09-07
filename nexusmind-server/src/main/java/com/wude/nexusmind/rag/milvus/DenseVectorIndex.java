package com.wude.nexusmind.rag.milvus;

import java.util.List;

public interface DenseVectorIndex {

    void ensureCollectionReady(String collectionName, int dimension);

    void ensureExistingCollectionReady(String collectionName, int dimension);

    void upsert(String collectionName, List<VectorIndexEntity> entities, int dimension);

    void deleteByDocumentId(String collectionName, long documentId);

    List<DenseVectorHit> search(String collectionName,
                                long knowledgeBaseId,
                                float[] queryVector,
                                int topK,
                                int dimension);
}
