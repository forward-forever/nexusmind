package com.wude.nexusmind.agent.memory;

import com.wude.nexusmind.agent.memory.domain.AgentMessageEntity;
import com.wude.nexusmind.agent.memory.domain.AgentMessageRole;
import com.wude.nexusmind.agent.memory.domain.AgentSessionEntity;
import com.wude.nexusmind.agent.memory.mapper.AgentMessageMapper;
import com.wude.nexusmind.agent.memory.mapper.AgentSessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConversationMemoryServiceTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void createsServerGeneratedSessionWhenIdIsAbsent() {
        Fixture fixture = fixture();
        when(fixture.sessions.insert(any())).thenReturn(1);

        String sessionId = fixture.service.resolveSession(33L, null);

        assertThat(sessionId).hasSize(36);
        ArgumentCaptor<AgentSessionEntity> captor = ArgumentCaptor.forClass(AgentSessionEntity.class);
        verify(fixture.sessions).insert(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo(sessionId);
        assertThat(captor.getValue().getKnowledgeBaseId()).isEqualTo(33L);
    }

    @Test
    void loadsExistingSessionAndEnforcesKnowledgeBaseOwnership() {
        Fixture valid = fixture();
        when(valid.sessions.findById(SESSION_ID)).thenReturn(Optional.of(session(33L)));
        assertThat(valid.service.resolveSession(33L, SESSION_ID)).isEqualTo(SESSION_ID);

        Fixture crossKb = fixture();
        when(crossKb.sessions.findById(SESSION_ID)).thenReturn(Optional.of(session(44L)));
        assertThatThrownBy(() -> crossKb.service.resolveSession(33L, SESSION_ID))
                .isInstanceOf(AgentSessionKnowledgeBaseMismatchException.class);
        verify(crossKb.messages, never()).findRecentBySessionId(any(), anyInt());
    }

    @Test
    void rejectsUnknownAndMalformedSessionIds() {
        Fixture fixture = fixture();
        when(fixture.sessions.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.resolveSession(33L, SESSION_ID))
                .isInstanceOf(AgentSessionNotFoundException.class);
        assertThatThrownBy(() -> fixture.service.resolveSession(33L, "abc"))
                .isInstanceOf(InvalidAgentSessionIdException.class);
    }

    @Test
    void loadsOnlyConfiguredWindowAndRestoresChronologicalRoleOrder() {
        Fixture fixture = fixture();
        when(fixture.messages.findRecentBySessionId(SESSION_ID, 4)).thenReturn(List.of(
                message(6L, AgentMessageRole.ASSISTANT, "A3"),
                message(5L, AgentMessageRole.USER, "U3"),
                message(4L, AgentMessageRole.ASSISTANT, "A2"),
                message(3L, AgentMessageRole.USER, "U2")));

        List<Message> result = fixture.service.loadRecentMessages(SESSION_ID, 4);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(message -> message.getText())
                .containsExactly("U2", "A2", "U3", "A3");
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        verify(fixture.messages).findRecentBySessionId(SESSION_ID, 4);
    }

    @Test
    void sanitizesHistoricalAssistantCitationsWithoutMutatingPersistenceEntity() {
        Fixture fixture = fixture();
        AgentMessageEntity stored = message(
                2L, AgentMessageRole.ASSISTANT, "Read View ... [S1]，RC ... [S12]。");
        when(fixture.messages.findRecentBySessionId(SESSION_ID, 1)).thenReturn(List.of(stored));

        List<Message> result = fixture.service.loadRecentMessages(SESSION_ID, 1);

        assertThat(result.get(0).getText()).isEqualTo("Read View ... ，RC ... 。");
        assertThat(stored.getContent()).isEqualTo("Read View ... [S1]，RC ... [S12]。");
    }

    @Test
    void sessionsDoNotShareMessages() {
        Fixture fixture = fixture();
        String sessionA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String sessionB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        when(fixture.messages.findRecentBySessionId(sessionA, 12)).thenReturn(List.of(
                message(1L, AgentMessageRole.USER, "代号蓝鲸47")));
        when(fixture.messages.findRecentBySessionId(sessionB, 12)).thenReturn(List.of());

        assertThat(fixture.service.loadRecentMessages(sessionA, 12))
                .extracting(Message::getText).containsExactly("代号蓝鲸47");
        assertThat(fixture.service.loadRecentMessages(sessionB, 12)).isEmpty();
    }

    @Test
    void appendsExactlyUserAndFinalAssistantThenTouchesSession() {
        Fixture fixture = fixture();
        when(fixture.messages.insert(any())).thenReturn(1);
        when(fixture.sessions.touch(SESSION_ID)).thenReturn(1);

        fixture.service.appendSuccessfulTurn(SESSION_ID, "question", "answer [S1]");

        ArgumentCaptor<AgentMessageEntity> captor = ArgumentCaptor.forClass(AgentMessageEntity.class);
        verify(fixture.messages, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AgentMessageEntity::getRole)
                .containsExactly(AgentMessageRole.USER, AgentMessageRole.ASSISTANT);
        assertThat(captor.getAllValues()).extracting(AgentMessageEntity::getContent)
                .containsExactly("question", "answer [S1]");
        verify(fixture.sessions).touch(SESSION_ID);
    }

    private static Fixture fixture() {
        AgentSessionMapper sessions = mock(AgentSessionMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        return new Fixture(
                new AgentConversationMemoryService(
                        sessions, messages, new HistoricalCitationSanitizer()),
                sessions, messages);
    }

    private static AgentSessionEntity session(long knowledgeBaseId) {
        return new AgentSessionEntity(SESSION_ID, knowledgeBaseId);
    }

    private static AgentMessageEntity message(long id, AgentMessageRole role, String content) {
        AgentMessageEntity message = new AgentMessageEntity(SESSION_ID, role, content);
        message.setId(id);
        return message;
    }

    private record Fixture(AgentConversationMemoryService service,
                           AgentSessionMapper sessions,
                           AgentMessageMapper messages) {
    }
}
