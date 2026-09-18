package com.wude.nexusmind.agent.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NexusMcpToolNamePrefixGeneratorTest {

    private final NexusMcpToolNamePrefixGenerator generator =
            new NexusMcpToolNamePrefixGenerator();

    @Test
    void sanitizesServerAndToolNamesIntoExplicitNamespace() {
        assertThat(generator.prefixedToolName("demo-server", "query-weather"))
                .isEqualTo("mcp_demo_server_query_weather");
        assertThat(generator.prefixedToolName("12306-mcp", "get tickets"))
                .isEqualTo("mcp_12306_mcp_get_tickets");
    }

    @Test
    void truncatesLongNamesDeterministicallyWithStableHash() {
        String first = generator.prefixedToolName(
                "a-very-long-external-mcp-server-name", "an-even-longer-tool-name-that-keeps-going");
        String second = generator.prefixedToolName(
                "a-very-long-external-mcp-server-name", "an-even-longer-tool-name-that-keeps-going");

        assertThat(first).hasSizeLessThanOrEqualTo(64).isEqualTo(second)
                .matches("[A-Za-z0-9_]+");
    }
}
