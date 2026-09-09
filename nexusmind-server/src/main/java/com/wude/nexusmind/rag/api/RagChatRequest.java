package com.wude.nexusmind.rag.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagChatRequest(
        @NotBlank @Size(max = 4000) String question,
        @Min(1) @Max(10) Integer topK
) {
}
