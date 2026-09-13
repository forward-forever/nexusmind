package com.wude.nexusmind.rag.milvus;

import java.util.List;

public interface Bm25SparseIndex {

    List<Bm25SparseHit> search(String collectionName,
                               long knowledgeBaseId,
                               String query,
                               int topK,
                               int dimension);
}
