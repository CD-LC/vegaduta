package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.brief.dto.LedgerDtos.RehearsalStarted;
import com.agenticai.core.brief.dto.LedgerDtos.RehearsalView;
import com.agenticai.core.brief.model.Brief;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rehearsal: the brief's personas against the applied primary agent, every session labelled, tools never on. */
class BriefRehearsalServiceTest {

    /** Records what the simulation feature was asked to do. Its interface has no way to switch tools on. */
    static final class FakePort implements RehearsalPort {
        final Map<String, String[]> personas = new LinkedHashMap<>();
        final List<Object[]> sessionsCreated = new ArrayList<>();
        final List<SessionInfo> sessions = new ArrayList<>();
        final Map<String, List<RunInfo>> runs = new LinkedHashMap<>();

        @Override
        public void ensurePersona(String key, String name, String description, String objective) {
            personas.putIfAbsent(key, new String[] {name, description, objective});
        }

        @Override
        public String createSession(String agentId, List<String> personaKeys, int maxTurns, String createdBy) {
            String id = "sess-" + (sessions.size() + 1);
            sessionsCreated.add(new Object[] {agentId, personaKeys, maxTurns, createdBy});
            sessions.add(new SessionInfo(id, createdBy, "RUNNING", Instant.parse("2026-09-26T12:00:00Z").plusSeconds(sessions.size())));
            return id;
        }

        @Override
        public List<SessionInfo> sessions(String agentId) {
            return sessions;
        }

        @Override
        public List<RunInfo> runs(String agentId, String sessionId) {
            return runs.getOrDefault(sessionId, List.of());
        }
    }

    private final EnvoyKit kit = new EnvoyKit();
    private final FakePort port = new FakePort();
    private BriefRehearsalService service;
    private UUID id;

    private static Brief withPersonas(Brief b, List<Brief.Persona> personas) {
        return new Brief(b.briefVersion(), b.title(), b.goal(), b.locale(), b.clarifications(), b.trust(), b.agents(),
            b.procedures(), b.workflows(), b.tools(), b.mcpServers(), b.knowledge(), b.secrets(), b.channels(), b.evalSet(),
            new Brief.Rehearsal(personas), b.narrative());
    }

    private static List<Brief.Persona> twoPersonas() {
        return List.of(
            new Brief.Persona("Angry customer", List.of("impatient", "terse"), "Where is my refund? This is the third time."),
            new Brief.Persona("Confused new user", List.of("polite"), "Hi, I do not know where to start."));
    }

    @BeforeEach
    void applyABriefWithPersonas() {
        service = new BriefRehearsalService(kit.store, kit.ledger, port);
        id = kit.savedAndApplied(withPersonas(EnvoyKit.richBrief(), twoPersonas()));
    }

    @Test
    void everyBriefPersonaTalksToThePrimaryAgentInOneLabelledSession() {
        RehearsalStarted started = service.rehearse(id);

        assertThat(started.label()).isEqualTo("rehearsal");
        assertThat(started.agentRef()).isEqualTo("a1");
        assertThat(started.agentId()).isEqualTo(kit.entityIdOf(id, "a1"));
        assertThat(started.sessionIds()).containsExactly("sess-1");
        assertThat(started.personas()).containsExactly("Angry customer", "Confused new user");
        assertThat(started.note()).contains("tools off").contains("nothing is sent");
        Object[] call = port.sessionsCreated.get(0);
        assertThat(call[0]).isEqualTo(kit.entityIdOf(id, "a1"));
        assertThat((List<?>) call[1]).hasSize(2);
        assertThat((Integer) call[2]).isEqualTo(BriefRehearsalService.MAX_TURNS);
        assertThat((String) call[3]).startsWith("rehearsal:brief:" + id + ":");
    }

    @Test
    void thePersonasCarryTheBriefsTraitsAndOpeningLine() {
        service.rehearse(id);

        String[] angry = port.personas.values().stream().filter(p -> p[0].contains("Angry")).findFirst().orElseThrow();
        assertThat(angry[0]).startsWith("Rehearsal: ");
        assertThat(angry[1]).contains("impatient, terse");
        assertThat(angry[2]).contains("Where is my refund? This is the third time.").contains("Traits: impatient, terse");
    }

    @Test
    void aSecondRehearsalReusesTheSamePersonasInsteadOfPilingUpNewOnes() {
        service.rehearse(id);
        service.rehearse(id);

        assertThat(port.personas).hasSize(2);
        assertThat(port.sessionsCreated).hasSize(2);
        assertThat(port.sessionsCreated.get(1)[1]).isEqualTo(port.sessionsCreated.get(0)[1]);
    }

    @Test
    void anEditedPersonaGetsItsOwnKeyAndOtherBriefsNeverShareOne() {
        Brief.Persona a = twoPersonas().get(0);
        Brief.Persona edited = new Brief.Persona(a.name(), a.traits(), "A different opening.");

        assertThat(BriefRehearsalService.personaKey(id, a)).isNotEqualTo(BriefRehearsalService.personaKey(id, edited));
        assertThat(BriefRehearsalService.personaKey(id, a)).isNotEqualTo(BriefRehearsalService.personaKey(UUID.randomUUID(), a));
        assertThat(BriefRehearsalService.personaKey(id, a).length()).isLessThanOrEqualTo(64);
    }

    @Test
    void aBriefThatIsNotRunningCannotBeRehearsed() {
        UUID draft = kit.save(withPersonas(EnvoyKit.richBrief(), twoPersonas()));

        assertThatThrownBy(() -> service.rehearse(draft)).isInstanceOf(BriefConflictException.class).hasMessageContaining("apply");
        assertThat(port.sessionsCreated).isEmpty();
    }

    @Test
    void aBriefWithNoPersonasRehearsesAgainstTheGenericPair() {
        UUID none = kit.savedAndApplied(EnvoyKit.richBrief());

        RehearsalStarted started = service.rehearse(none);

        assertThat(started.label()).isEqualTo("rehearsal");
        assertThat(started.personas()).containsExactly("A typical user", "A difficult user");
        assertThat(port.sessionsCreated).hasSize(1);
        assertThat((List<?>) port.sessionsCreated.get(0)[1]).hasSize(2);
    }

    @Test
    void theListShowsOnlyThisBriefsRehearsalsEachLabelledWithItsRuns() {
        service.rehearse(id);
        port.sessions.add(new RehearsalPort.SessionInfo("other", "rehearsal:brief:" + UUID.randomUUID() + ":x", "COMPLETED", Instant.now()));
        port.sessions.add(new RehearsalPort.SessionInfo("hand-made", "someone@example.com", "COMPLETED", Instant.now()));
        port.runs.put("sess-1", List.of(new RehearsalPort.RunInfo("run-1", "Rehearsal: Angry customer", "COMPLETED", "PASSED",
            "It held its position.", 4)));

        List<RehearsalView> views = service.rehearsals(id);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).label()).isEqualTo("rehearsal");
        assertThat(views.get(0).sessionId()).isEqualTo("sess-1");
        assertThat(views.get(0).runs()).hasSize(1);
        assertThat(views.get(0).runs().get(0).verdict()).isEqualTo("PASSED");
        assertThat(views.get(0).runs().get(0).turns()).isEqualTo(4);
    }
}
