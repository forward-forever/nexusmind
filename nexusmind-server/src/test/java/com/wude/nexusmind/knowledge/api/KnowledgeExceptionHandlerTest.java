package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.rag.rerank.RerankClientException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeExceptionHandlerTest {

    @Test
    void mapsRerankProviderFailureToSafeBadGateway() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/knowledge-bases/7/search");

        ResponseEntity<ApiError> response = new KnowledgeExceptionHandler()
                .rerankProviderFailure(new RerankClientException("provider body must not leak"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RERANK_PROVIDER_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Rerank provider request failed");
    }
}
