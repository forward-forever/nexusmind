package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;

public record DocumentUploadResult(KnowledgeDocument document, boolean duplicate) {
}
