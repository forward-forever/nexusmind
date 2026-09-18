package com.wude.nexusmind.agent.mcp;

import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpPolicyValidatorTest {

    @Test
    void matchingDisabledSwitchesAreValid() {
        assertThatCode(() -> new McpPolicyValidator(
                new McpProperties(false, List.of(), Duration.ofSeconds(8)),
                agent(true, Duration.ofSeconds(30)),
                new MockEnvironment().withProperty("spring.ai.mcp.client.enabled", "false")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSwitchMismatchAndTimeoutAtOrBeyondAgentDeadline() {
        assertThatThrownBy(() -> new McpPolicyValidator(
                new McpProperties(true, List.of(), Duration.ofSeconds(8)),
                agent(true, Duration.ofSeconds(30)),
                new MockEnvironment().withProperty("spring.ai.mcp.client.enabled", "false")))
                .hasMessageContaining("must match");

        assertThatThrownBy(() -> new McpPolicyValidator(
                new McpProperties(true, List.of(), Duration.ofSeconds(30)),
                agent(true, Duration.ofSeconds(30)),
                new MockEnvironment().withProperty("spring.ai.mcp.client.enabled", "true")))
                .hasMessageContaining("shorter");
    }

    private static AgentProperties agent(boolean enabled, Duration maxDuration) {
        return new AgentProperties(
                enabled, 5, maxDuration,
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                new AgentProperties.DocumentContext(1, 1),
                new AgentProperties.Memory(12),
                new AgentProperties.SessionConcurrency(Duration.ofSeconds(45)),
                new AgentProperties.ToolResult(5_000, 12_000));
    }
}
