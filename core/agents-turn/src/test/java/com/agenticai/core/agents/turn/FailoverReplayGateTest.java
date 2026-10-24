package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.TurnToolActivity.Outcome;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Whether a turn that already ran tools may be replayed on another provider. "No text was streamed
 * yet" used to be the only test, but a tool-calling turn commonly streams no text while it works, so a
 * failover re-ran the whole turn - sends, creates and deletes included.
 */
class FailoverReplayGateTest {

    /** The tool classification: true = safe to replay, false = unsafe, absent = unclassified. */
    private static final Map<String, Boolean> CLASSIFIED = Map.of(
            "get_order", true,
            "send_email", false,
            "create_invoice", false);

    private static final Function<String, Optional<Boolean>> LOOKUP =
            name -> Optional.ofNullable(CLASSIFIED.get(name));

    private final ChatResilienceProperties props = new ChatResilienceProperties();
    private final TurnToolActivity activity = new TurnToolActivity();

    private FailoverReplayGate.Verdict check() {
        return new FailoverReplayGate(props, LOOKUP).check(activity);
    }

    private void ran(String tool, Outcome outcome) {
        activity.end(activity.begin(tool), outcome);
    }

    @Test
    void aTurnThatRanNoToolMayBeReplayed() {
        assertThat(check().allowed()).isTrue();
        assertThat(new FailoverReplayGate(props, LOOKUP).check(null).allowed()).isTrue();
    }

    @Test
    void readOnlyToolsDoNotStopAReplay() {
        ran("get_order", Outcome.EXECUTED);
        ran("get_order", Outcome.EXECUTED);

        assertThat(check().allowed()).isTrue();
    }

    @Test
    void aToolWithSideEffectsThatRanAndIsNotJournaledStopsTheReplay() {
        ran("get_order", Outcome.EXECUTED);
        ran("send_email", Outcome.EXECUTED);

        FailoverReplayGate.Verdict verdict = check();

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.toolsThatRan()).contains("send_email").doesNotContain("get_order");
    }

    @Test
    void aSideEffectToolTheEffectJournalRecordedMayBeReplayedBecauseARepeatIsServedFromIt() {
        ran("send_email", Outcome.JOURNALED);

        assertThat(check().allowed()).isTrue();
    }

    @Test
    void theJournalCanBeDistrustedByConfiguration() {
        props.setReplayAfterJournaledEffects(false);
        ran("send_email", Outcome.JOURNALED);

        assertThat(check().allowed()).isFalse();
    }

    @Test
    void oneUnjournaledCallAmongJournaledOnesStillStopsTheReplay() {
        ran("send_email", Outcome.JOURNALED);
        ran("send_email", Outcome.FAILED); // threw after the provider may have acted: no journal record

        assertThat(check().allowed()).isFalse();
    }

    @Test
    void aToolThatIsStillRunningStopsTheReplayWhateverItIs() {
        activity.begin("get_order"); // a read, but it has not finished and nothing is known of it

        FailoverReplayGate.Verdict verdict = check();

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.toolsThatRan()).contains("get_order");
    }

    @Test
    void callsAGuardAnsweredOrTheJournalServedDoNotCount() {
        ran("send_email", Outcome.NOT_RUN);
        ran("create_invoice", Outcome.REPLAYED);

        assertThat(check().allowed()).isTrue();
    }

    @Test
    void anUnclassifiedToolIsJudgedByItsNameAndAnUnknownMutatorStopsTheReplay() {
        ran("search_catalog", Outcome.EXECUTED);
        assertThat(check().allowed()).as("a read verb").isTrue();

        ran("mystery_tool", Outcome.EXECUTED);
        assertThat(check().allowed()).as("unknown: fail safe").isFalse();
    }

    @Test
    void theNamesInTheVerdictAreTheOnesThatActed() {
        ran("send_email", Outcome.EXECUTED);
        ran("create_invoice", Outcome.EXECUTED);
        ran("get_order", Outcome.EXECUTED);

        assertThat(check().toolsThatRan()).isEqualTo("send_email, create_invoice");
    }
}
