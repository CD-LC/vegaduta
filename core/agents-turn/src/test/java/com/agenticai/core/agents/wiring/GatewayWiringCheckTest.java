package com.agenticai.core.agents.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.skills.LessonLearningGateway;
import com.agenticai.core.agents.skills.PreferenceSignalGateway;
import com.agenticai.core.agents.skills.RoutineObservationGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway;
import com.agenticai.core.agents.turnjournal.InMemoryTurnJournal;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GatewayWiringCheckTest {

    @AfterEach
    void reset() {
        TurnJournalGateway.install(null, false);
        SkillLearningGateway.install(null);
        RoutineObservationGateway.install(null);
        PreferenceSignalGateway.install(null);
        LessonLearningGateway.install(null);
    }

    @Test
    void aFeatureThatIsOffNeedsNoGateway() {
        GatewayWiringCheck check = new GatewayWiringCheck(new MockEnvironment(), false);

        assertTrue(check.problems().isEmpty());
        check.onReady();
    }

    @Test
    void aFeatureSwitchedOnWhoseGatewayWasNeverInstalledIsReported() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.turn-journal.enabled", "true")
                        .withProperty("app.skills.learning.enabled", "true");

        List<String> problems = new GatewayWiringCheck(env, false).problems();

        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("turn journal") && p.contains("TurnJournalGateway")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("skill learning") && p.contains("SkillLearningGateway")));
    }

    @Test
    void anInstalledGatewayIsNotAProblem() {
        TurnJournalGateway.install(new InMemoryTurnJournal(), true);
        SkillLearningGateway.install(
                new SkillLearningGateway.Observer() {
                    public boolean enabled() {
                        return true;
                    }

                    public void onTurn(SkillLearningGateway.TurnOutcome outcome) {}
                });
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.turn-journal.enabled", "true")
                        .withProperty("app.skills.learning.enabled", "true");

        assertTrue(new GatewayWiringCheck(env, false).problems().isEmpty());
    }

    @Test
    void aFeatureNeedsAllItsFlagsToBeOn() {
        // The learning beans are conditional on BOTH flags; one alone switches nothing on.
        MockEnvironment env = new MockEnvironment().withProperty("app.learning.routines.enabled", "true");
        assertTrue(new GatewayWiringCheck(env, false).problems().isEmpty());

        env.withProperty("app.learning.proposals.enabled", "true");
        List<String> problems = new GatewayWiringCheck(env, false).problems();
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("RoutineObservationGateway"));
    }

    @Test
    void strictModeRefusesToStartAndTheDefaultOnlyLogs() {
        MockEnvironment env = new MockEnvironment().withProperty("app.turn-journal.enabled", "true");

        new GatewayWiringCheck(env, false).onReady(); // logs an ERROR, does not throw
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> new GatewayWiringCheck(env, true).onReady());
        assertTrue(refused.getMessage().contains("TurnJournalGateway"), refused.getMessage());
    }

    @Test
    void everyStaticGatewayThatHasAnInstallMethodIsChecked() {
        Set<String> covered =
                GatewayWiringCheck.standardFeatures().stream()
                        .map(GatewayWiringCheck.Feature::gateway)
                        .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        TurnJournalGateway.class.getSimpleName(),
                        SkillLearningGateway.class.getSimpleName(),
                        RoutineObservationGateway.class.getSimpleName(),
                        PreferenceSignalGateway.class.getSimpleName(),
                        LessonLearningGateway.class.getSimpleName()),
                covered);
        // A new *Gateway holder with install(...) must be added to the check: look for any this list does not know.
        for (Class<?> gateway :
                List.of(
                        TurnJournalGateway.class,
                        SkillLearningGateway.class,
                        RoutineObservationGateway.class,
                        PreferenceSignalGateway.class,
                        LessonLearningGateway.class)) {
            assertTrue(
                    java.util.Arrays.stream(gateway.getMethods()).anyMatch(m -> m.getName().equals("install")),
                    gateway.getSimpleName());
        }
    }
}
