package com.wude.nexusmind.agent.api;

import com.wude.nexusmind.agent.memory.AgentSessionKnowledgeBaseMismatchException;
import com.wude.nexusmind.agent.memory.AgentSessionNotFoundException;
import com.wude.nexusmind.agent.memory.InvalidAgentSessionIdException;
import com.wude.nexusmind.knowledge.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AgentChatController.class)
public class AgentExceptionHandler {

    @ExceptionHandler(AgentSessionNotFoundException.class)
    ResponseEntity<ApiError> sessionNotFound(AgentSessionNotFoundException exception,
                                             HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "AGENT_SESSION_NOT_FOUND",
                "Agent session does not exist", request);
    }

    @ExceptionHandler(AgentSessionKnowledgeBaseMismatchException.class)
    ResponseEntity<ApiError> sessionKnowledgeBaseMismatch(
            AgentSessionKnowledgeBaseMismatchException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "AGENT_SESSION_KNOWLEDGE_BASE_MISMATCH",
                "Agent session belongs to another knowledge base", request);
    }

    @ExceptionHandler(InvalidAgentSessionIdException.class)
    ResponseEntity<ApiError> invalidSessionId(InvalidAgentSessionIdException exception,
                                              HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_AGENT_SESSION_ID",
                exception.getMessage(), request);
    }

    private static ResponseEntity<ApiError> response(HttpStatus status,
                                                     String code,
                                                     String message,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(), status.value(), code, message, request.getRequestURI()));
    }
}
