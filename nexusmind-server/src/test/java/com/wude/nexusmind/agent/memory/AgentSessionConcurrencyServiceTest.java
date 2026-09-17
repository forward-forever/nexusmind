package com.wude.nexusmind.agent.memory;

import com.wude.nexusmind.agent.memory.domain.AgentSessionEntity;
import com.wude.nexusmind.agent.memory.mapper.AgentSessionMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionConcurrencyServiceTest {

    private static final String SESSION = "11111111-1111-1111-1111-111111111111";
    private static final String RUN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    void createsNewSessionAlreadyOwnedByCurrentRun() {
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.insertWithLease(org.mockito.ArgumentMatchers.anyString(),
                eq(33L), eq(RUN), anyLong())).thenReturn(1);

        String session = new AgentSessionConcurrencyService(mapper)
                .acquire(33L, null, RUN, Duration.ofSeconds(45));

        assertThat(session).hasSize(36);
        verify(mapper).insertWithLease(eq(session), eq(33L), eq(RUN), eq(45_000_000L));
    }

    @Test
    void reportsBusyWhenAtomicAcquireDoesNotOwnExistingSession() {
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.findById(SESSION)).thenReturn(Optional.of(session(33L)));
        when(mapper.acquireLease(SESSION, 33L, RUN, 45_000_000L)).thenReturn(0);

        assertThatThrownBy(() -> new AgentSessionConcurrencyService(mapper)
                .acquire(33L, SESSION, RUN, Duration.ofSeconds(45)))
                .isInstanceOf(AgentSessionBusyException.class);
    }

    @Test
    void releaseIsFencedByRunId() {
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);
        when(mapper.releaseLease(SESSION, RUN)).thenReturn(0);

        assertThat(new AgentSessionConcurrencyService(mapper).release(SESSION, RUN)).isFalse();
        verify(mapper).releaseLease(SESSION, RUN);
    }

    private static AgentSessionEntity session(long knowledgeBaseId) {
        return new AgentSessionEntity(SESSION, knowledgeBaseId);
    }
}
