package com.wude.nexusmind.rag.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.model.embedding=none",
                "nexusmind.vector.enabled=false",
                "nexusmind.milvus.enabled=false",
                "nexusmind.rag.enabled=false"
        }
)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CHAT_BASE_URL", matches = ".+")
class RealChatModelLocalIT {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void qwenStreamsFinalAnswerContentWithTheProvidedCitation() {
        String answer = chatClientBuilder.build()
                .prompt()
                .system("Answer only from the reference. Cite the supplied source ID.")
                .user("REFERENCE [S1]: InnoDB resolves a detected deadlock by rolling back a victim transaction. "
                        + "QUESTION: How does InnoDB resolve the deadlock?")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(60));

        assertThat(answer).isNotBlank().contains("[S1]");
    }
}
