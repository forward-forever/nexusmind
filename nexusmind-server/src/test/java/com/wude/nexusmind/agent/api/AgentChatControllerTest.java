package com.wude.nexusmind.agent.api;

import com.wude.nexusmind.agent.application.AgentChatService;
import com.wude.nexusmind.agent.model.AgentSource;
import com.wude.nexusmind.agent.stream.AgentStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentChatControllerTest {

    @Test
    void mapsEachTypedAgentEventToTheMatchingSseEventName() {
        AgentChatService service = mock(AgentChatService.class);
        AgentSource source = new AgentSource("S1", 1L, 2L, "doc.pdf", 3, null);
        List<AgentStreamEvent> events = List.of(
                AgentStreamEvent.assistantDelta("run-1", "session-1", "answer"),
                AgentStreamEvent.toolStart("run-1", "session-1", "inv-1", "search_knowledge_base",
                        Map.of("query", "MVCC")),
                AgentStreamEvent.toolResult("run-1", "session-1", "inv-1", "search_knowledge_base",
                        10, 1, List.of(source)),
                AgentStreamEvent.toolError("run-1", "session-1", "inv-2", "search_knowledge_base",
                        "TOOL_FAILED", "failed"),
                AgentStreamEvent.done("run-1", "session-1", 1, 2, 20, List.of(source)),
                AgentStreamEvent.error("run-1", "session-1", "AGENT_MODEL_ERROR", "failed"));
        when(service.chat(33L, null, "question")).thenReturn(Flux.fromIterable(events));

        List<ServerSentEvent<AgentStreamEvent>> sse = new AgentChatController(service)
                .chat(33L, new AgentChatRequest(null, "question"))
                .collectList().block();

        assertThat(sse).isNotNull();
        assertThat(sse).extracting(ServerSentEvent::event)
                .containsExactly("assistant_delta", "tool_start", "tool_result",
                        "tool_error", "done", "error");
        assertThat(sse).allSatisfy(event -> {
            assertThat(event.data()).isNotNull();
            assertThat(event.data().runId()).isEqualTo("run-1");
            assertThat(event.data().sessionId()).isEqualTo("session-1");
        });
    }
}
