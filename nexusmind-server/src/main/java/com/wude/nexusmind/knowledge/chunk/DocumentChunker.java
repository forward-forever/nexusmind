package com.wude.nexusmind.knowledge.chunk;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.parser.ParsedDocument;

import java.util.List;

public interface DocumentChunker {

    List<KnowledgeChunk> chunk(ParsedDocument parsedDocument, long knowledgeBaseId, long documentId);
}
