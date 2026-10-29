package com.agenticai.core.agents.tools.scratchpad.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.scratchpad.AgentScratchpadService;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadNoteView;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadToolCallback;
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

    private AgentService agentService;
    private AgentScratchpadService scratchpadService;
    private AgentScratchpadController controller;

    private static ScratchpadNoteView view(String scope, String key, String body) {
        return new ScratchpadNoteView(
                key,
                scope,
                body == null ? 0 : body.getBytes().length,
                Instant.parse("2026-01-02T03:04:05Z"),
                body);
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
        List<ScratchpadNoteView> notes = List.of(view("session", "plan", null));
        when(scratchpadService.listViews(
                        eq(AGENT), eq(ScratchpadToolCallback.sessionUuid(rawSession))))
                .thenReturn(notes);

        AgentScratchpadController.ScratchpadListView out = controller.list(AGENT, rawSession);

        verify(agentService).requireAccess(AGENT, PermissionLevel.VIEWER);
        assertThat(out.count()).isEqualTo(1);
        assertThat(out.totalBytes()).isZero();
        assertThat(out.notes().get(0).key()).isEqualTo("plan");
        assertThat(out.notes().get(0).scope()).isEqualTo("session");
        assertThat(out.notes().get(0).body()).isNull();
    }

    @Test
    void theAgentLiteralAndAnOmittedSessionBothReadTheLongTermPad() {
        List<ScratchpadNoteView> notes = List.of(view("agent", "lt", null));
        when(scratchpadService.listViews(eq(AGENT), isNull())).thenReturn(notes);

        assertThat(controller.list(AGENT, "agent").notes().get(0).scope()).isEqualTo("agent");
        assertThat(controller.list(AGENT, null).notes().get(0).scope()).isEqualTo("agent");
        verify(scratchpadService, times(2)).listViews(eq(AGENT), isNull());
    }

    @Test
    void readReturnsTheBodyAnd404sWhenMissing() {
        Optional<ScratchpadNoteView> found = Optional.of(view("agent", "k", "body text"));
        when(scratchpadService.readView(any(), any(), eq("k"))).thenReturn(found);

        assertThat(controller.read(AGENT, "k", null).getBody().body()).isEqualTo("body text");
        when(scratchpadService.readView(any(), any(), eq("gone"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.read(AGENT, "gone", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDisabledScratchpadAnswers404() {
        when(scratchpadService.listViews(any(), any()))
                .thenThrow(new IllegalStateException("not enabled"));

        assertThatThrownBy(() -> controller.list(AGENT, "s"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
