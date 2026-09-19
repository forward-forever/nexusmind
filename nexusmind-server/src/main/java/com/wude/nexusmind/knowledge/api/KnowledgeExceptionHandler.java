package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.exception.DocumentNotFoundException;
import com.wude.nexusmind.knowledge.exception.DocumentParsingException;
import com.wude.nexusmind.knowledge.exception.DocumentStorageException;
import com.wude.nexusmind.knowledge.exception.DocumentTooLargeException;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentStateException;
import com.wude.nexusmind.knowledge.exception.InvalidDocumentIndexStateException;
import com.wude.nexusmind.knowledge.exception.KnowledgeBaseNotFoundException;
import com.wude.nexusmind.knowledge.exception.UnsupportedDocumentTypeException;
import com.wude.nexusmind.rag.exception.EmbeddingConfigurationMismatchException;
import com.wude.nexusmind.rag.exception.EmbeddingGenerationException;
import com.wude.nexusmind.rag.exception.KnowledgeBaseInactiveException;
import com.wude.nexusmind.rag.exception.VectorIndexException;
import com.wude.nexusmind.rag.rerank.RerankClientException;
import com.wude.nexusmind.knowledge.task.DocumentTaskNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

@RestControllerAdvice
@ConditionalOnProperty(name = "spring.datasource.url")
public class KnowledgeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExceptionHandler.class);

    @ExceptionHandler(KnowledgeBaseNotFoundException.class)
    public ResponseEntity<ApiError> knowledgeBaseNotFound(KnowledgeBaseNotFoundException exception,
                                                           HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "KNOWLEDGE_BASE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> documentNotFound(DocumentNotFoundException exception,
                                                       HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(DocumentTaskNotFoundException.class)
    public ResponseEntity<ApiError> documentTaskNotFound(DocumentTaskNotFoundException exception,
                                                          HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "DOCUMENT_TASK_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    public ResponseEntity<ApiError> unsupportedType(UnsupportedDocumentTypeException exception,
                                                     HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", exception.getMessage(), request);
    }

    @ExceptionHandler({DocumentTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> fileTooLarge(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", exception.getMessage(), request);
    }

    @ExceptionHandler(DocumentParsingException.class)
    public ResponseEntity<ApiError> parserFailure(DocumentParsingException exception,
                                                   HttpServletRequest request) {
        log.warn("Document parsing failed for request {}", request.getRequestURI(), exception);
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "PARSER_FAILURE", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidDocumentStateException.class)
    public ResponseEntity<ApiError> invalidState(InvalidDocumentStateException exception,
                                                  HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "INVALID_DOCUMENT_STATE", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidDocumentIndexStateException.class)
    public ResponseEntity<ApiError> invalidIndexState(InvalidDocumentIndexStateException exception,
                                                       HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "INVALID_DOCUMENT_INDEX_STATE", exception.getMessage(), request);
    }

    @ExceptionHandler(EmbeddingConfigurationMismatchException.class)
    public ResponseEntity<ApiError> embeddingConfigurationMismatch(
            EmbeddingConfigurationMismatchException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "EMBEDDING_CONFIGURATION_MISMATCH",
                exception.getMessage(), request);
    }

    @ExceptionHandler(KnowledgeBaseInactiveException.class)
    public ResponseEntity<ApiError> knowledgeBaseInactive(KnowledgeBaseInactiveException exception,
                                                           HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "KNOWLEDGE_BASE_INACTIVE", exception.getMessage(), request);
    }

    @ExceptionHandler(EmbeddingGenerationException.class)
    public ResponseEntity<ApiError> embeddingFailure(EmbeddingGenerationException exception,
                                                      HttpServletRequest request) {
        log.error("Embedding request failed at {}: errorType={}",
                request.getRequestURI(), exception.getClass().getSimpleName());
        return response(HttpStatus.BAD_GATEWAY, "EMBEDDING_FAILURE",
                "Embedding generation failed", request);
    }

    @ExceptionHandler(RerankClientException.class)
    public ResponseEntity<ApiError> rerankProviderFailure(RerankClientException exception,
                                                           HttpServletRequest request) {
        log.error("Rerank provider request failed at {}: errorType={}",
                request.getRequestURI(), exception.getClass().getSimpleName());
        return response(HttpStatus.BAD_GATEWAY, "RERANK_PROVIDER_ERROR",
                "Rerank provider request failed", request);
    }

    @ExceptionHandler(VectorIndexException.class)
    public ResponseEntity<ApiError> vectorIndexFailure(VectorIndexException exception,
                                                        HttpServletRequest request) {
        log.error("Vector index operation failed at {}", request.getRequestURI(), exception);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "VECTOR_INDEX_FAILURE",
                exception.getMessage(), request);
    }

    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<ApiError> storageFailure(DocumentStorageException exception,
                                                    HttpServletRequest request) {
        log.error("Document storage failed for request {}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_FAILURE",
                "Document storage operation failed", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected request failure at {}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected server error", request);
    }

    private static ResponseEntity<ApiError> response(HttpStatus status,
                                                     String code,
                                                     String message,
                                                     HttpServletRequest request) {
        ApiError body = new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
