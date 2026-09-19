package com.wude.nexusmind.rag.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.wude.nexusmind.rag.retrieval.RetrieverType;

public record RetrievalSearchRequest(
        @NotBlank @Size(max = 4000) String query,
        @Min(1) @Max(20) Integer topK,
        RetrieverType retrieverType
) {
}
