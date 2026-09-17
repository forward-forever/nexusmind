package com.wude.nexusmind.rag.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.model.chat=none",
                "nexusmind.document-task.worker-enabled=false",
                "nexusmind.vector.enabled=false",
                "nexusmind.milvus.enabled=false",
                "nexusmind.rag.enabled=false"
        }
)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "EMBEDDING_BASE_URL", matches = ".+")
class RealEmbeddingLocalIT {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void dashScopeEmbeddingHasExpectedDimension() {
        float[] vector = embeddingModel.embed("数据库事务为什么会产生死锁？");

        assertThat(vector).hasSize(1024);
    }
}
