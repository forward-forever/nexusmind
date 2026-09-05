package com.wude.nexusmind.knowledge.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1024) String description,
        @NotBlank @Size(max = 128) String embeddingModel,
        @Min(1) @Max(100000) int embeddingDimension) {
}
