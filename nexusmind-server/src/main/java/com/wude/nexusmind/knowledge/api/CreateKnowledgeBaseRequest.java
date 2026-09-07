package com.wude.nexusmind.knowledge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1024) String description) {
}
