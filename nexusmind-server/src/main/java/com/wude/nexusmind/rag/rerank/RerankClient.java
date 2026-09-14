package com.wude.nexusmind.rag.rerank;

import java.util.List;

public interface RerankClient {

    RerankResult rerank(String query, List<String> documents, int topN);
}
