package com.wude.nexusmind.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentChatRequest(
        String sessionId,
        @NotBlank(message = "message is required")
        @Size(max = 4000, message = "message must not exceed 4000 characters")
        String message) {
}
