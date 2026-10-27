package com.agenticai.core.agents.tools.scratchpad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The read-only operator surface: VIEWER-gated, session strings mapped exactly like the tool's own
 * sessionUuid resolution, "agent" addressing the long-term pad, disabled -> 404.
 */
class AgentScratchpadControllerTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID SESSION_PAD = UUID.randomUUID();

    private AgentService agentService;
    private AgentScratchpadService scratchpadService;
    private AgentScratchpadController controller;

    private static ScratchpadNote note(UUID sessionId, String key, String body) {
        ScratchpadNote n = mock(ScratchpadNote.class);
        org.mockito.Mockito.lenient().when(n.getKey()).thenReturn(key);
        org.mockito.Mockito.lenient().when(n.getBody()).thenReturn(body);
        org.mockito.Mockito.lenient().when(n.getSessionId()).thenReturn(sessionId);
        org.mockito.Mockito.lenient().when(n.getByteSize()).thenReturn(body.getBytes().length);
        org.mockito.Mockito.lenient()
                .when(n.getUpdatedAt())
                .thenReturn(Instant.parse("2026-01-02T03:04:05Z"));
        return n;
    }

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        scratchpadService = mock(AgentScratchpadService.class);
        controller = new AgentScratchpadController(agentService, scratchpadService);
    }

    @Test
    void listRequiresViewerAndMapsTheRawSessionLikeTheToolDoes() {
        String rawSession = "sess-42";
        List<ScratchpadNote> notes = List.of(note(SESSION_PAD, "plan", "do the thing"));
        when(scratchpadService.list(eq(AGENT), eq(ScratchpadToolCallback.sessionUuid(rawSession))))
                .thenReturn(notes);

        AgentScratchpadController.ScratchpadListView view = controller.list(AGENT, rawSession);

        verify(agentService).requireAccess(AGENT, PermissionLevel.VIEWER);
        assertThat(view.count()).isEqualTo(1);
        assertThat(view.totalBytes()).isEqualTo("do the thing".getBytes().length);
        assertThat(view.notes().get(0).key()).isEqualTo("plan");
        assertThat(view.notes().get(0).scope()).isEqualTo("session");
        assertThat(view.notes().get(0).body()).isNull();
    }

    @Test
    void theAgentLiteralAndAnOmittedSessionBothReadTheLongTermPad() {
        List<ScratchpadNote> notes = List.of(note(null, "lt", "long term"));
        when(scratchpadService.list(eq(AGENT), isNull())).thenReturn(notes);

        assertThat(controller.list(AGENT, "agent").notes().get(0).scope()).isEqualTo("agent");
        assertThat(controller.list(AGENT, null).notes().get(0).scope()).isEqualTo("agent");
        verify(scratchpadService, org.mockito.Mockito.times(2)).list(eq(AGENT), isNull());
    }

    @Test
    void readReturnsTheBodyAnd404sWhenMissing() {
        Optional<ScratchpadNote> found = Optional.of(note(null, "k", "body text"));
        when(scratchpadService.read(any(), any(), eq("k"))).thenReturn(found);

        assertThat(controller.read(AGENT, "k", null).getBody().body()).isEqualTo("body text");
        when(scratchpadService.read(any(), any(), eq("gone"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.read(AGENT, "gone", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDisabledScratchpadAnswers404() {
        when(scratchpadService.list(any(), any()))
                .thenThrow(new IllegalStateException("not enabled"));

        assertThatThrownBy(() -> controller.list(AGENT, "s"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
