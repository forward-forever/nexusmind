package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;

public record UploadDocumentResponse(KnowledgeDocument document, boolean duplicate) {
}
