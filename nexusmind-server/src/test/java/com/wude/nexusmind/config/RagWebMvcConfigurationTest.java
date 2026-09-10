package com.wude.nexusmind.config;

import com.wude.nexusmind.model.config.RagChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RagWebMvcConfigurationTest {

    @Test
    void configuresMvcWithItsLongerIndependentTimeout() {
        RagChatProperties properties = new RagChatProperties(
                "qwen3.5-flash", 0.2, 5, 10, 12_000,
                Duration.ofSeconds(120), Duration.ofSeconds(150));
        RagWebMvcConfiguration configuration = new RagWebMvcConfiguration(properties);
        AsyncSupportConfigurer asyncSupport = mock(AsyncSupportConfigurer.class);

        configuration.configureAsyncSupport(asyncSupport);

        verify(asyncSupport).setDefaultTimeout(150_000L);
    }
}
