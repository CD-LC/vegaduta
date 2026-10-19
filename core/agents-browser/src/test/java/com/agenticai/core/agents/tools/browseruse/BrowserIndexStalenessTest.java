package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.browseruse.BrowserIndexStaleness.Probe;
import com.agenticai.core.agents.tools.browseruse.BrowserIndexStaleness.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The index-staleness rules (DOM generation counter + element identity), with no browser involved.
 */
class BrowserIndexStalenessTest {

    private static final long GEN = 10L;

    private static BrowserSnapshot.Data snapshot() {
        return new BrowserSnapshot.Data(
                "https://x.example/",
                "T",
                GEN,
                2,
                0,
                1000,
                800,
                List.of(
                        new BrowserSnapshot.Element(
                                1, "button", "Search", "", List.of(), "", List.of(), true),
                        new BrowserSnapshot.Element(
                                2, "link", "Pricing", "", List.of(), "/pricing", List.of(), true)),
                "");
    }

    private static Probe ok() {
        return new Probe(true, GEN, GEN, true, true, "button", "Search");
    }

    @Test
    void freshSnapshotAndUnchangedDomIsOk() {
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, ok(), false)).isEqualTo(Status.OK);
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, ok(), true)).isEqualTo(Status.OK);
    }

    @Test
    void noSnapshotTakenYet() {
        assertThat(BrowserIndexStaleness.evaluate(null, 1, ok(), false))
                .isEqualTo(Status.NO_SNAPSHOT);
        assertThat(BrowserIndexStaleness.message(Status.NO_SNAPSHOT, 1))
                .contains("browser_snapshot first");
    }

    @Test
    void indexOutsideTheSnapshotIsOutOfRange() {
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 0, ok(), false))
                .isEqualTo(Status.OUT_OF_RANGE);
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 3, ok(), false))
                .isEqualTo(Status.OUT_OF_RANGE);
        assertThat(BrowserIndexStaleness.message(Status.OUT_OF_RANGE, 3)).contains("Index 3");
    }

    @Test
    void navigationWipesTheInPageRegistryAndStalesEveryIndex() {
        Probe wiped = new Probe(false, -1, -1, false, false, "", "");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, wiped, false))
                .isEqualTo(Status.PAGE_CHANGED);
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, null, false))
                .isEqualTo(Status.PAGE_CHANGED);
    }

    @Test
    void aRegistryFromAnotherSnapshotIsStale() {
        Probe other = new Probe(true, GEN, GEN + 3, true, true, "button", "Search");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, other, false))
                .isEqualTo(Status.PAGE_CHANGED);
    }

    @Test
    void strictModeTreatsAnyGenerationChangeAsStale() {
        Probe moved = new Probe(true, GEN + 1, GEN, true, true, "button", "Search");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, moved, true))
                .isEqualTo(Status.PAGE_CHANGED);
    }

    @Test
    void relaxedModeToleratesUnrelatedChangesWhenTheElementIsStillTheSame() {
        Probe moved = new Probe(true, GEN + 5, GEN, true, true, "button", "Search");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, moved, false))
                .isEqualTo(Status.OK);
    }

    @Test
    void relaxedModeRefusesWhenTheElementWasReplacedOrRenamed() {
        Probe detached = new Probe(true, GEN + 1, GEN, true, false, "button", "Search");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, detached, false))
                .isEqualTo(Status.PAGE_CHANGED);
        Probe renamed = new Probe(true, GEN + 1, GEN, true, true, "button", "Pay now");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, renamed, false))
                .isEqualTo(Status.ELEMENT_GONE);
        Probe retyped = new Probe(true, GEN + 1, GEN, true, true, "link", "Search");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, retyped, false))
                .isEqualTo(Status.ELEMENT_GONE);
    }

    @Test
    void anInPlaceRelabelWithoutAGenerationChangeIsRefusedInBothModes() {
        // A text-node edit adds/removes no element, so the generation counter does not move.
        Probe relabelled = new Probe(true, GEN, GEN, true, true, "button", "Pay $50");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, relabelled, false))
                .isEqualTo(Status.ELEMENT_GONE);
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, relabelled, true))
                .isEqualTo(Status.ELEMENT_GONE);
        Probe retyped = new Probe(true, GEN, GEN, true, true, "link", "Search");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, retyped, false))
                .isEqualTo(Status.ELEMENT_GONE);
    }

    @Test
    void anElementThatVanishedWithoutAGenerationChangeIsGone() {
        Probe missing = new Probe(true, GEN, GEN, false, false, "", "");
        assertThat(BrowserIndexStaleness.evaluate(snapshot(), 1, missing, false))
                .isEqualTo(Status.ELEMENT_GONE);
    }

    @Test
    void theErrorTellsTheModelToTakeANewSnapshot() {
        assertThat(BrowserIndexStaleness.message(Status.PAGE_CHANGED, 7))
                .contains("page changed")
                .contains("index 7")
                .contains("browser_snapshot");
        assertThat(BrowserIndexStaleness.message(Status.ELEMENT_GONE, 7))
                .contains("browser_snapshot");
        assertThat(BrowserIndexStaleness.message(Status.OK, 7)).isEmpty();
    }
}
