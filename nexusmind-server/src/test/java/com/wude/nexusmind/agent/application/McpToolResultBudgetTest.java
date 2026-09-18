package com.wude.nexusmind.agent.application;

import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.mcp.BudgetedMcpToolCallback;
import com.wude.nexusmind.agent.mcp.McpProperties;
import com.wude.nexusmind.agent.mcp.McpToolResultBudgeter;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.tool.KnowledgeSearchTool;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import com.wude.nexusmind.support.TestTokenSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolResultBudgetTest {

    @Test
    void oversizedResultBecomesValidExplicitEnvelopeAndUsesRunBudget() throws Exception {
        Fixture fixture = fixture(500, 800, Duration.ofSeconds(30));
        BudgetedMcpToolCallback callback = fixture.callback(input -> "x".repeat(2_000));

        String result = callback.call("{}", fixture.toolContext());

        assertThat(result).contains("\"truncated\":true")
                .contains("truncated by NexusMind context policy")
                .contains("… [truncated]");
        assertThat(new ObjectMapper().readTree(result).get("content").asText())
                .endsWith("… [truncated]");
        assertThat(fixture.run.tokenBudget().usedTokens()).isBetween(1, 500);
        assertThat(fixture.events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result");
        assertThat(fixture.run.sourceRegistry().snapshot()).isEmpty();
    }

    @Test
    void nativeAndMcpResultsShareOneRunBudget() {
        Fixture fixture = fixture(500, 700, Duration.ofSeconds(30));
        fixture.run.tokenBudget().allocate(500,
                allowed -> new AgentRunTokenBudget.BudgetedValue<>("native", 450));
        BudgetedMcpToolCallback callback = fixture.callback(input -> "m".repeat(1_000));

        String result = callback.call("{}", fixture.toolContext());

        assertThat(result).contains("\"truncated\":true");
        assertThat(fixture.run.tokenBudget().usedTokens()).isLessThanOrEqualTo(700);
        assertThat(fixture.run.tokenBudget().remainingTokens()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void argumentsAreRedactedAndTransportFailureIsNotRetried() {
        Fixture fixture = fixture(500, 800, Duration.ofSeconds(30));
        AtomicInteger invocations = new AtomicInteger();
        BudgetedMcpToolCallback callback = fixture.callback(input -> {
            invocations.incrementAndGet();
            throw new IllegalStateException("remote URL with secret=abc");
        });

        assertThatThrownBy(() -> callback.call(
                "{\"secret\":\"abc\",\"query\":\"weather\"}", fixture.toolContext()))
                .isInstanceOf(com.wude.nexusmind.agent.mcp.McpToolExecutionException.class)
                .hasMessage("MCP tool execution failed: mcp_demo_echo");

        assertThat(invocations).hasValue(1);
        assertThat(fixture.events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_error");
        assertThat(fixture.events.get(0).arguments()).isEmpty();
        assertThat(fixture.events.toString()).doesNotContain("abc", "remote URL");
    }

    @Test
    void insufficientAgentDeadlinePreventsMcpInvocation() {
        Fixture fixture = fixture(500, 800, Duration.ofSeconds(5));
        AtomicInteger invocations = new AtomicInteger();
        BudgetedMcpToolCallback callback = fixture.callback(input -> {
            invocations.incrementAndGet();
            return "never";
        });

        assertThatThrownBy(() -> callback.call("{}", fixture.toolContext()))
                .isInstanceOf(AgentExecutionException.class)
                .satisfies(error -> assertThat(((AgentExecutionException) error).code())
                        .isEqualTo("AGENT_TIMEOUT"));
        assertThat(invocations).hasValue(0);
        assertThat(fixture.events).isEmpty();
    }

    private static Fixture fixture(int perCall, int perRun, Duration maxDuration) {
        AgentProperties properties = new AgentProperties(
                true, 5, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                new AgentProperties.DocumentContext(1, 1),
                new AgentProperties.Memory(12),
                new AgentProperties.SessionConcurrency(Duration.ofSeconds(45)),
                new AgentProperties.ToolResult(perCall, perRun));
        List<AgentStreamEvent> events = new ArrayList<>();
        AgentRunContext run = new AgentRunContext(
                "run", "session", 33L, 0, maxDuration,
                Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneId.of("UTC")),
                events::add, perRun);
        McpToolResultBudgeter budgeter = new McpToolResultBudgeter(
                new ObjectMapper(), TestTokenSupport.estimator(),
                TestTokenSupport.truncator(), properties);
        return new Fixture(run, events, budgeter,
                new McpProperties(true, List.of("mcp_demo_echo"), Duration.ofSeconds(8)));
    }

    private record Fixture(AgentRunContext run,
                           List<AgentStreamEvent> events,
                           McpToolResultBudgeter budgeter,
                           McpProperties mcpProperties) {

        private BudgetedMcpToolCallback callback(java.util.function.Function<String, String> action) {
            return new BudgetedMcpToolCallback(new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("mcp_demo_echo")
                            .description("external echo")
                            .inputSchema("{\"type\":\"object\"}").build();
                }

                @Override
                public String call(String toolInput) {
                    return action.apply(toolInput);
                }
            }, budgeter, mcpProperties);
        }

        private ToolContext toolContext() {
            return new ToolContext(Map.of(KnowledgeSearchTool.CONTEXT_AGENT_RUN, run));
        }
    }
}
