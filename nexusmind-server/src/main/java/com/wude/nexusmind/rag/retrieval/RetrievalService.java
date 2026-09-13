package com.wude.nexusmind.rag.retrieval;

public interface RetrievalService {

    RetrieverType type();

    RetrievalResult retrieve(long knowledgeBaseId, String query, int topK);
}
