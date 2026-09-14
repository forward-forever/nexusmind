package com.wude.nexusmind.rag.evaluation;

import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import com.wude.nexusmind.knowledge.mapper.KnowledgeChunkMapper;
import com.wude.nexusmind.knowledge.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BenchmarkDatasetResolverService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkDatasetResolverService.class);
    private static final Comparator<KnowledgeChunk> CHUNK_ORDER = Comparator
            .comparing(KnowledgeChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(KnowledgeChunk::getId, Comparator.nullsLast(Long::compareTo));

    private final DocumentService documentService;
    private final KnowledgeChunkMapper chunkMapper;

    public BenchmarkDatasetResolverService(DocumentService documentService,
                                           KnowledgeChunkMapper chunkMapper) {
        this.documentService = documentService;
        this.chunkMapper = chunkMapper;
    }

    public BenchmarkDatasetResolution resolve(long documentId, List<BenchmarkSourceCase> sourceCases) {
        if (documentId <= 0) {
            throw new DatasetValidationException("documentId must be positive");
        }
        if (sourceCases == null || sourceCases.isEmpty()) {
            throw new DatasetValidationException("Benchmark source must contain at least one question");
        }

        KnowledgeDocument document = documentService.get(documentId);
        validateDocument(documentId, document);
        List<KnowledgeChunk> chunks = List.copyOf(chunkMapper.findByDocumentId(documentId));
        if (chunks.isEmpty()) {
            throw new DatasetValidationException("Document " + documentId + " has no chunks");
        }

        Map<Integer, List<KnowledgeChunk>> chunksByPage = indexChunks(document, chunks);
        List<RetrievalEvaluationCase> goldenCases = new ArrayList<>(sourceCases.size());
        List<BenchmarkDatasetResolution.QueryMapping> mappings = new ArrayList<>(sourceCases.size());
        Set<Integer> referencedPages = new HashSet<>();
        Set<Long> resolvedChunks = new HashSet<>();
        Set<Integer> warnedPages = new HashSet<>();

        for (BenchmarkSourceCase sourceCase : sourceCases) {
            LinkedHashSet<Integer> stablePages = new LinkedHashSet<>(sourceCase.expectedPages());
            LinkedHashSet<Long> relevantChunkIds = new LinkedHashSet<>();
            for (Integer page : stablePages) {
                List<KnowledgeChunk> pageChunks = chunksByPage.get(page);
                if (pageChunks == null || pageChunks.isEmpty()) {
                    throw missingPage(sourceCase, page);
                }
                if (pageChunks.size() > 2 && warnedPages.add(page)) {
                    LOGGER.warn("Page {} resolved to {} chunks; verify benchmark chunking. chunkIds={}",
                            page, pageChunks.size(), pageChunks.stream().map(KnowledgeChunk::getId).toList());
                }
                pageChunks.stream().map(KnowledgeChunk::getId).forEach(relevantChunkIds::add);
                referencedPages.add(page);
            }
            if (relevantChunkIds.isEmpty()) {
                throw new DatasetValidationException(
                        "Query " + sourceCase.id() + " resolved to no chunks" + conceptSuffix(sourceCase));
            }

            List<Long> ids = List.copyOf(relevantChunkIds);
            goldenCases.add(new RetrievalEvaluationCase(
                    sourceCase.id(),
                    document.getKnowledgeBaseId(),
                    sourceCase.question(),
                    ids,
                    sourceCase.category(),
                    sourceCase.note()));
            mappings.add(new BenchmarkDatasetResolution.QueryMapping(
                    sourceCase.id(), List.copyOf(stablePages), ids));
            resolvedChunks.addAll(ids);
        }

        return new BenchmarkDatasetResolution(
                documentId,
                document.getKnowledgeBaseId(),
                goldenCases,
                mappings,
                referencedPages.size(),
                resolvedChunks.size());
    }

    private static void validateDocument(long requestedId, KnowledgeDocument document) {
        if (document.getId() == null || document.getId() != requestedId) {
            throw new DatasetValidationException("Loaded document does not match documentId " + requestedId);
        }
        if (document.getKnowledgeBaseId() == null || document.getKnowledgeBaseId() <= 0) {
            throw new DatasetValidationException("Document " + requestedId + " has no valid knowledgeBaseId");
        }
        if (document.getStatus() != DocumentStatus.READY) {
            throw new DatasetValidationException(
                    "Document " + requestedId + " must be READY, actual status: " + document.getStatus());
        }
        if (document.getIndexStatus() != DocumentIndexStatus.INDEXED) {
            throw new DatasetValidationException(
                    "Document " + requestedId + " must be INDEXED, actual indexStatus: "
                            + document.getIndexStatus());
        }
    }

    private static Map<Integer, List<KnowledgeChunk>> indexChunks(KnowledgeDocument document,
                                                                  List<KnowledgeChunk> chunks) {
        Map<Integer, List<KnowledgeChunk>> chunksByPage = new HashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            if (chunk.getId() == null || chunk.getId() <= 0) {
                throw new DatasetValidationException("Document " + document.getId() + " contains a chunk without a valid ID");
            }
            if (!document.getId().equals(chunk.getDocumentId())
                    || !document.getKnowledgeBaseId().equals(chunk.getKnowledgeBaseId())) {
                throw new DatasetValidationException(
                        "Chunk " + chunk.getId() + " does not belong to document " + document.getId()
                                + " and knowledge base " + document.getKnowledgeBaseId());
            }
            if (chunk.getPageNo() != null) {
                chunksByPage.computeIfAbsent(chunk.getPageNo(), ignored -> new ArrayList<>()).add(chunk);
            }
        }
        chunksByPage.values().forEach(pageChunks -> pageChunks.sort(CHUNK_ORDER));
        return chunksByPage;
    }

    private static DatasetValidationException missingPage(BenchmarkSourceCase sourceCase, int page) {
        return new DatasetValidationException(
                "Query " + sourceCase.id() + " expected page " + page
                        + " but the document has no chunk for that page" + conceptSuffix(sourceCase));
    }

    private static String conceptSuffix(BenchmarkSourceCase sourceCase) {
        return sourceCase.expectedConcept() == null
                ? ""
                : "; expectedConcept=" + sourceCase.expectedConcept();
    }
}
