package com.wude.nexusmind.rag.retrieval;

/**
 * 召回服务接口
 */
public interface RetrievalService {

    /**
     * 召回服务类型
     */
    RetrieverType type();

    /**
     * 召回
     *
     * @param knowledgeBaseId 知识库ID
     * @param query 查询内容
     * @param topK 返回结果数量
     * @return 召回结果
     */
    RetrievalResult retrieve(long knowledgeBaseId, String query, int topK);
}
