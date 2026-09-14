package com.wude.nexusmind.rag.rerank;

import com.wude.nexusmind.model.config.RerankProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlibabaQwenRerankClientTest {

    private static final String BASE_URL = "https://workspace.example/api/v1/";
    private static final String ENDPOINT = BASE_URL + AlibabaQwenRerankClient.RERANK_PATH;

    @Test
    void sendsOfficialTextRerankProtocolAndMapsIndexScoreAndUsage() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer fake-test-key")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model":"qwen3.7-text-rerank",
                          "input":{
                            "query":"InnoDB 为什么会死锁？",
                            "documents":["死锁检测使用等待图。","Redis 支持缓存淘汰。"]
                          },
                          "parameters":{
                            "top_n":2,
                            "instruct":"Given a web search query, retrieve relevant passages that answer the query."
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "output":{"results":[
                            {"index":0,"relevance_score":0.9334521178273196},
                            {"index":1,"relevance_score":0.041}
                          ]},
                          "usage":{"prompt_tokens":79,"total_tokens":79},
                          "request_id":"request-1"
                        }
                        """, MediaType.APPLICATION_JSON));
        AtomicLong nanoTime = new AtomicLong();
        AlibabaQwenRerankClient client = new AlibabaQwenRerankClient(
                builder.build(), properties(), () -> nanoTime.getAndAdd(15_000_000));

        RerankResult result = client.rerank(
                "InnoDB 为什么会死锁？",
                List.of("死锁检测使用等待图。", "Redis 支持缓存淘汰。"),
                2);

        assertThat(result.items()).containsExactly(
                new RerankResult.Item(0, 0.9334521178273196),
                new RerankResult.Item(1, 0.041));
        assertThat(result.usage()).isEqualTo(new RerankResult.Usage(79L, 79L));
        assertThat(result.latencyMs()).isEqualTo(15);
        server.verify();
    }

    @Test
    void convertsHttpFailureWithoutLeakingProviderResponseOrCredential() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());
        AlibabaQwenRerankClient client = new AlibabaQwenRerankClient(
                builder.build(), properties());

        assertThatThrownBy(() -> client.rerank("query", List.of("document"), 1))
                .isInstanceOf(RerankClientException.class)
                .hasMessage("Rerank provider request failed")
                .hasMessageNotContaining("fake-test-key");
        server.verify();
    }

    private static RerankProviderProperties properties() {
        return new RerankProviderProperties(
                true,
                "qwen3.7-text-rerank",
                "https://workspace.example/api/v1",
                "fake-test-key",
                Duration.ofSeconds(3),
                "Given a web search query, retrieve relevant passages that answer the query.");
    }
}
