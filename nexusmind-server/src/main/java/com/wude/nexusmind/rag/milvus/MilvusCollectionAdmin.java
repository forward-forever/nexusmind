package com.wude.nexusmind.rag.milvus;

public interface MilvusCollectionAdmin {

    void ensureCollectionReady(String collectionName, int dimension);

    void dropCollection(String collectionName);
}
