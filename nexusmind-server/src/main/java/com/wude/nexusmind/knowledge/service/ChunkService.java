package com.wude.nexusmind.knowledge.service;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.mapper.KnowledgeDocumentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
@Transactional(readOnly = true)
public class ChunkService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;

    public ChunkService(KnowledgeDocumentMapper documentMapper, KnowledgeChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public int append(long documentId, List<KnowledgeChunk> chunks) {
        KnowledgeDocument document = findDocument(documentId);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("At least one chunk is required");
        }
        for (KnowledgeChunk chunk : chunks) {
            if (chunk == null
                    || !Objects.equals(chunk.getDocumentId(), documentId)
                    || !Objects.equals(chunk.getKnowledgeBaseId(), document.getKnowledgeBaseId())) {
                throw new IllegalArgumentException("Every chunk must belong to the requested document and knowledge base");
            }
        }
        return chunkMapper.batchInsert(chunks);
    }

    public List<KnowledgeChunk> listByDocument(long documentId) {
        findDocument(documentId);
        return List.copyOf(chunkMapper.findByDocumentId(documentId));
    }

    @Transactional
    public int clearDocument(long documentId) {
        findDocument(documentId);
        return chunkMapper.deleteByDocumentId(documentId);
    }

    private KnowledgeDocument findDocument(long documentId) {
        return documentMapper.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
    }
}
