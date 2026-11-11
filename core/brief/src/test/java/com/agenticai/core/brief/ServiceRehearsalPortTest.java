package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.simulation.SimulationPersonaService;
import com.agenticai.core.quality.simulation.SimulationPersonaService.ResolvedPersona;
import com.agenticai.core.quality.simulation.SimulationRun;
import com.agenticai.core.quality.simulation.SimulationRunService;
import com.agenticai.core.quality.simulation.SimulationRunStatus;
import com.agenticai.core.quality.simulation.SimulationSession;
import com.agenticai.core.quality.simulation.SimulationSessionStatus;
import com.agenticai.core.quality.simulation.SimulationVerdict;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ServiceRehearsalPort}: idempotent persona creation, the create-race tolerance, and the
 * view mapping.
 */
class ServiceRehearsalPortTest {

    private static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SimulationPersonaService personas;
    private SimulationRunService runs;
    private ServiceRehearsalPort port;

    @BeforeEach
    void setUp() {
        personas = mock(SimulationPersonaService.class);
        runs = mock(SimulationRunService.class);
        port = new ServiceRehearsalPort(personas, runs);
    }

    private static ResolvedPersona persona(String key) {
        return new ResolvedPersona(key, "n", "d", "o", false);
    }

    @Test
    void anExistingPersonaIsNotCreatedAgain() {
        when(personas.listAll()).thenReturn(List.of(persona("angry")));

        port.ensurePersona("angry", "Angry", "d", "o");

        verify(personas, never()).createCustom(any(), any(), any(), any());
    }

    @Test
    void aMissingPersonaIsCreated() {
        when(personas.listAll()).thenReturn(List.of(persona("other")));

        port.ensurePersona("angry", "Angry", "desc", "obj");

        verify(personas).createCustom("angry", "Angry", "desc", "obj");
    }

    @Test
    void losingTheCreateRaceIsFineButAGenuineRejectionIsNot() {
        when(personas.listAll()).thenReturn(List.of()).thenReturn(List.of(persona("angry")));
        when(personas.createCustom("angry", "A", "d", "o"))
                .thenThrow(new IllegalArgumentException("already exists"));
        port.ensurePersona("angry", "A", "d", "o");

        when(personas.listAll()).thenReturn(List.of());
        when(personas.createCustom("bad", "B", "d", "o"))
                .thenThrow(new IllegalArgumentException("invalid key"));
        assertThatThrownBy(() -> port.ensurePersona("bad", "B", "d", "o"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid key");
    }

    @Test
    void createSessionReturnsTheNewSessionId() {
        SimulationSession session = mock(SimulationSession.class);
        when(session.getId()).thenReturn(SESSION);
        when(runs.createSession(AGENT, List.of("angry"), 6, "tester")).thenReturn(session);

        assertThat(port.createSession(AGENT.toString(), List.of("angry"), 6, "tester"))
                .isEqualTo(SESSION.toString());
    }

    @Test
    void sessionsAreMappedToTheirPortView() {
        SimulationSession session = mock(SimulationSession.class);
        Instant at = Instant.parse("2026-11-01T00:00:00Z");
        when(session.getId()).thenReturn(SESSION);
        when(session.getCreatedBy()).thenReturn("tester");
        when(session.getStatus()).thenReturn(SimulationSessionStatus.COMPLETED);
        when(session.getCreatedAt()).thenReturn(at);
        when(runs.listSessions(AGENT)).thenReturn(List.of(session));

        List<RehearsalPort.SessionInfo> out = port.sessions(AGENT.toString());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(SESSION.toString());
        assertThat(out.get(0).status()).isEqualTo("COMPLETED");
        assertThat(out.get(0).createdAt()).isEqualTo(at);
    }

    @Test
    void runsAreMappedWithAndWithoutAVerdict() {
        UUID runId = UUID.randomUUID();
        SimulationRun judged = mock(SimulationRun.class);
        when(judged.getId()).thenReturn(runId);
        when(judged.getPersonaName()).thenReturn("Angry");
        when(judged.getStatus()).thenReturn(SimulationRunStatus.PASSED);
        when(judged.getVerdict()).thenReturn(SimulationVerdict.PASSED);
        when(judged.getVerdictReason()).thenReturn("held the line");
        when(judged.getTurnCount()).thenReturn(5);
        SimulationRun running = mock(SimulationRun.class);
        when(running.getId()).thenReturn(UUID.randomUUID());
        when(running.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(running.getVerdict()).thenReturn(null);
        when(runs.listRuns(AGENT, SESSION)).thenReturn(List.of(judged, running));

        List<RehearsalPort.RunInfo> out = port.runs(AGENT.toString(), SESSION.toString());

        assertThat(out.get(0).verdict()).isEqualTo("PASSED");
        assertThat(out.get(0).verdictReason()).isEqualTo("held the line");
        assertThat(out.get(0).turns()).isEqualTo(5);
        assertThat(out.get(1).verdict()).isNull();
        assertThat(out.get(1).status()).isEqualTo("RUNNING");
    }
}
