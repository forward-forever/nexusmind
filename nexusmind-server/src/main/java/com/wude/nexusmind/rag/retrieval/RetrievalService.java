package com.wude.nexusmind.rag.retrieval;

public interface RetrievalService {

    RetrievalResult retrieve(long knowledgeBaseId, String query, int topK);
}
