package com.wude.nexusmind.rag.api;

import com.wude.nexusmind.rag.chat.RagChatService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RagChatControllerTest {

    @Test
    void springMvcSerializesTheFluxAsStableServerSentEvents() throws Exception {
        RagChatService service = mock(RagChatService.class);
        RagSourceResponse source = new RagSourceResponse(
                "S1", 101L, 10L, "mysql.pdf", 17, null, 0.82f);
        when(service.stream(7L, "question", 5)).thenReturn(Flux.just(
                new RagStreamEvent("sources", List.of(source), null, null, null, null, null),
                RagStreamEvent.delta("answer [S1]"),
                RagStreamEvent.done("qwen3.5-flash", 120L)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagChatController(service)).build();

        MvcResult initial = mockMvc.perform(post("/api/knowledge-bases/7/rag/stream")
                        .contentType("application/json")
                        .accept("text/event-stream")
                        .content("{\"question\":\"question\",\"topK\":5}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn();
        String body = completed.getResponse().getContentAsString();
        assertThat(completed.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(body).contains("data:");
        assertThat(body.indexOf("\"type\":\"sources\""))
                .isLessThan(body.indexOf("\"type\":\"delta\""));
        assertThat(body.indexOf("\"type\":\"delta\""))
                .isLessThan(body.indexOf("\"type\":\"done\""));
        assertThat(body).contains("\"id\":\"S1\"", "\"fileName\":\"mysql.pdf\"", "answer [S1]");
        assertThat(body).doesNotContain("contentPreview", "embedding");
    }
}
