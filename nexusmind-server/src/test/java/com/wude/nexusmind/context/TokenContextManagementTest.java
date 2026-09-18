package com.wude.nexusmind.context;

import com.wude.nexusmind.support.TestTokenSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenContextManagementTest {

    @Test
    void calculatesSeparateRagAndAgentMessageBudgetsFromCentralReserves() {
        TokenBudgetCalculator calculator = TestTokenSupport.calculator(
                new TokenBudgetProperties(100, 10, 20, 15));

        assertThat(calculator.ragMessageBudget()).isEqualTo(70);
        assertThat(calculator.agentMessageBudget()).isEqualTo(55);
        assertThat(calculator.ragContextBudget(
                List.of(new SystemMessage("1234"), new UserMessage("12")), 50))
                .isEqualTo(50);
    }

    @Test
    void rejectsInvalidGlobalReserveConfiguration() {
        assertThatThrownBy(() -> new TokenBudgetProperties(100, 30, 40, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leave prompt capacity");
    }

    @Test
    void truncatorUsesEstimatorAndIncludesVisibleMarkerInsideBudget() {
        TokenTextTruncator.TruncatedText result = TestTokenSupport.truncator()
                .truncateToEstimatedTokens("abcdefghijklmnopqrstuvwxyz", 20);

        assertThat(result.text()).endsWith("… [truncated]");
        assertThat(TestTokenSupport.estimator().estimate(result.text())).isLessThanOrEqualTo(20);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void truncatorNeverSplitsASurrogatePair() {
        TokenTextTruncator.TruncatedText result = TestTokenSupport.truncator()
                .truncateToEstimatedTokens("abc😀defghijklmnopqrstuvwxyz", 20);

        int markerIndex = result.text().indexOf(TokenTextTruncator.MARKER);
        assertThat(markerIndex).isPositive();
        assertThat(Character.isHighSurrogate(result.text().charAt(markerIndex - 1))).isFalse();
        assertThat(result.text()).endsWith("… [truncated]");
    }
}
