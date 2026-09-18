package com.wude.nexusmind.agent.mcp;

import com.wude.nexusmind.agent.application.AgentChatService;
import com.wude.nexusmind.agent.application.AgentModelTurnStreamer;
import com.wude.nexusmind.agent.config.AgentProperties;
import com.wude.nexusmind.agent.memory.AgentConversationMemoryService;
import com.wude.nexusmind.agent.memory.AgentSessionConcurrencyService;
import com.wude.nexusmind.agent.prompt.AgentPromptFactory;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import com.wude.nexusmind.agent.tool.AgentToolCatalog;
import com.wude.nexusmind.context.TokenBudgetProperties;
import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import com.wude.nexusmind.knowledge.domain.KnowledgeBaseStatus;
import com.wude.nexusmind.knowledge.service.KnowledgeBaseService;
import com.wude.nexusmind.model.config.RagChatProperties;
import com.wude.nexusmind.rag.retrieval.RetrieverType;
import com.wude.nexusmind.resilience.ProviderStreamingRetry;
import com.wude.nexusmind.support.TestTokenSupport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpProtocolLocalIT {

    private static ConfigurableApplicationContext serverContext;
    private static McpSyncClient client;
    private static ToolCallback rawMcpCallback;
    private static ToolCallback allowedMcpCallback;
    private static McpDiscoveryResponse discovery;

    @BeforeAll
    static void startLocalStreamableHttpServerAndClient() {
        serverContext = new SpringApplicationBuilder(TestMcpServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--spring.ai.mcp.client.enabled=false",
                        "--spring.ai.mcp.server.enabled=true",
                        "--spring.ai.mcp.server.protocol=STREAMABLE",
                        "--spring.ai.mcp.server.name=test-server",
                        "--spring.ai.mcp.server.version=1.0.0",
                        "--spring.ai.mcp.server.type=SYNC");
        int port = ((ServletWebServerApplicationContext) serverContext)
                .getWebServer().getPort();
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/mcp")
                        .build();
        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .clientInfo(new McpSchema.Implementation("nexusmind-local-it", "1.0.0"))
                .build();
        McpSchema.InitializeResult initialized = client.initialize();
        assertThat(initialized.serverInfo().name()).isEqualTo("test-server");
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(client)
                .toolNamePrefixGenerator(new NexusMcpToolNamePrefixGenerator())
                .build();
        rawMcpCallback = provider.getToolCallbacks()[0];

        AgentProperties agentProperties = agentProperties();
        McpProperties mcpProperties = new McpProperties(
                true, List.of("mcp_test_server_echo"), Duration.ofSeconds(3));
        McpToolResultBudgeter budgeter = new McpToolResultBudgeter(
                new ObjectMapper(), TestTokenSupport.estimator(),
                TestTokenSupport.truncator(), agentProperties);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mcpToolCallbackProvider", provider);
        beans.addBean("mcpToolResultBudgeter", budgeter);
        McpToolRegistry registry = new McpToolRegistry(
                mcpProperties,
                beans.getBeanProvider(SyncMcpToolCallbackProvider.class),
                beans.getBeanProvider(McpToolResultBudgeter.class));
        discovery = registry.discovery();
        allowedMcpCallback = registry.allowedCallbacks().get(0);
    }

    @AfterAll
    static void stopLocalServerAndClient() {
        if (client != null) {
            client.closeGracefully();
        }
        if (serverContext != null) {
            serverContext.close();
        }
    }

    @Test
    void initializesDiscoversPrefixesAllowListsAndInvokesRealMcpTool() {
        assertThat(client.isInitialized()).isTrue();
        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                .containsExactly("echo");
        assertThat(rawMcpCallback.getToolDefinition().name())
                .isEqualTo("mcp_test_server_echo");
        assertThat(discovery.discoveredCount()).isEqualTo(1);
        assertThat(discovery.allowedCount()).isEqualTo(1);
        assertThat(discovery.tools()).containsExactly(
                new McpDiscoveredTool("mcp_test_server_echo", true));

        String result = rawMcpCallback.call("{\"message\":\"hello\"}");

        assertThat(result).contains("hello");
    }

    @Test
    void realMcpCallbackRunsInsideExistingAgentToolLoop() {
        AgentProperties agentProperties = agentProperties();
        AgentToolCatalog catalog = new AgentToolCatalog(
                List.of(), List.of(allowedMcpCallback), new ObjectMapper(),
                TestTokenSupport.estimator(), 2_048);
        QueueStreamer streamer = new QueueStreamer(
                Flux.just(toolCall("call-1", "mcp_test_server_echo",
                        "{\"message\":\"hello\"}")),
                Flux.just(text("External echo returned hello.")));

        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(33L);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE);
        when(knowledgeBaseService.get(33L)).thenReturn(kb);
        AgentConversationMemoryService memory = mock(AgentConversationMemoryService.class);
        AgentSessionConcurrencyService concurrency = mock(AgentSessionConcurrencyService.class);
        when(concurrency.acquire(anyLong(), any(), anyString(), any()))
                .thenReturn("11111111-1111-1111-1111-111111111111");
        when(memory.loadRecentMessages(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());

        AgentChatService service = new AgentChatService(
                knowledgeBaseService, streamer,
                ToolCallingManager.builder().toolExecutionExceptionProcessor(
                        new DefaultToolExecutionExceptionProcessor(true)).build(),
                catalog, new AgentPromptFactory(), memory, concurrency,
                agentProperties,
                new RagChatProperties("qwen3.5-flash", 0.2, 5, 10, 12_000,
                        Duration.ofSeconds(120), Duration.ofSeconds(150)),
                Clock.systemUTC(), ProviderStreamingRetry.noRetry(),
                TestTokenSupport.calculator());

        List<AgentStreamEvent> events = service.chat(33L, "echo hello externally")
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("tool_start", "tool_result", "assistant_delta", "done");
        assertThat(events.get(0).toolName()).isEqualTo("mcp_test_server_echo");
        assertThat(events.get(0).arguments()).isEmpty();
        assertThat(events.get(3).toolCallCount()).isEqualTo(1);
        assertThat(events.get(3).modelTurnCount()).isEqualTo(2);
    }

    private static AgentProperties agentProperties() {
        return new AgentProperties(
                true, 5, Duration.ofSeconds(30),
                new AgentProperties.KnowledgeSearch(RetrieverType.DENSE, 5),
                new AgentProperties.DocumentContext(1, 1),
                new AgentProperties.Memory(12),
                new AgentProperties.SessionConcurrency(Duration.ofSeconds(45)),
                new AgentProperties.ToolResult(5_000, 12_000));
    }

    private static ChatClientResponse toolCall(String id, String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, arguments)))
                .build();
        return response(message);
    }

    private static ChatClientResponse text(String value) {
        return response(new AssistantMessage(value));
    }

    private static ChatClientResponse response(AssistantMessage message) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(message))), Map.of());
    }

    private static final class QueueStreamer implements AgentModelTurnStreamer {
        private final Deque<Flux<ChatClientResponse>> turns;

        @SafeVarargs
        private QueueStreamer(Flux<ChatClientResponse>... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public Flux<ChatClientResponse> stream(Prompt prompt) {
            return turns.removeFirst();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(EchoToolConfiguration.class)
    static class TestMcpServerApplication {
    }

    @Configuration(proxyBeanMethods = false)
    static class EchoToolConfiguration {
        @Bean
        List<ToolCallback> echoToolCallbacks() {
            return List.of(ToolCallbacks.from(new EchoTool())[0]);
        }
    }

    static final class EchoTool {
        @Tool(name = "echo", description = "Echo a deterministic message")
        String echo(@ToolParam(description = "message to echo") String message) {
            return message;
        }
    }
}
