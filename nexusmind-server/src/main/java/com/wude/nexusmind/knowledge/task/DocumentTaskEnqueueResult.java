package com.wude.nexusmind.knowledge.task;

import com.wude.nexusmind.knowledge.task.infrastructure.persistence.KnowledgeDocumentTask;

public record DocumentTaskEnqueueResult(KnowledgeDocumentTask task, boolean accepted) {
}
