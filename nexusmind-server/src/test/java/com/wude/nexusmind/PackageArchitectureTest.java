package com.wude.nexusmind;

import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentMessageMapper;
import com.wude.nexusmind.agent.memory.infrastructure.persistence.AgentSessionEntity;
import com.wude.nexusmind.knowledge.task.domain.DocumentTaskStatus;
import com.wude.nexusmind.knowledge.task.infrastructure.persistence.DocumentTaskMapper;
import com.wude.nexusmind.rag.api.RetrievalController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PackageArchitectureTest {

    @Test
    void persistenceAndPublicRetrievalTypesUseFeatureFirstPackages() {
        assertThat(AgentMessageMapper.class.getPackageName()).endsWith("memory.infrastructure.persistence");
        assertThat(AgentSessionEntity.class.getPackageName()).endsWith("memory.infrastructure.persistence");
        assertThat(DocumentTaskMapper.class.getPackageName()).endsWith("task.infrastructure.persistence");
        assertThat(DocumentTaskStatus.class.getPackageName()).endsWith("task.domain");
        assertThat(RetrievalController.class.getSimpleName()).isEqualTo("RetrievalController");
    }
}
