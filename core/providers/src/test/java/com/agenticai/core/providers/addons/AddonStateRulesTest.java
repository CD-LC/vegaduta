package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.addons.AddonDefinition.Availability;
import com.agenticai.core.providers.addons.AddonDefinition.Category;
import com.agenticai.core.providers.addons.AddonDefinition.Compose;
import com.agenticai.core.providers.addons.AddonDefinition.Kind;
import com.agenticai.core.providers.addons.AddonStateRules.Facts;
import com.agenticai.core.providers.addons.AddonStateRules.Pull;
import com.agenticai.core.providers.addons.AddonStateRules.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every branch of the add-on state machine. */
class AddonStateRulesTest {

    private static AddonDefinition compose(boolean defaultOn) {
        return new AddonDefinition("svc", "Svc", "d", Category.SEARCH, Kind.COMPOSE_PROFILE, Availability.AVAILABLE, 1, 1,
                false, "MIT", true, List.of(), defaultOn, "cmd --search", "docs/x.md", List.of(), null,
                new Compose(List.of(), List.of("--search"), Map.of()), null);
    }

    private static AddonDefinition model() {
        return new AddonDefinition("m", "M", "d", Category.MODELS, Kind.MODEL_PULL, Availability.AVAILABLE, 1, 1,
                false, "MIT", true, List.of(), false, "ollama pull x", "docs/x.md", List.of(), "x", null, null);
    }

    private static AddonDefinition planned() {
        return new AddonDefinition("p", "P", "Planned.", Category.MEDIA, Kind.PLANNED, Availability.PLANNED, 0, 0,
                false, "", true, List.of(), false, "", "docs/x.md", List.of(), null, null, null);
    }

    /** A stored row: wish {@code desired} at revision {@code rev}, with an optional report for {@code reportRev}. */
    private static AddonState row(boolean desired, long rev, String status, Long reportRev, String message) {
        AddonState row = new AddonState("svc", desired, 1, "owner");
        for (long i = 1; i < rev; i++) {
            row.change(desired, "owner");
        }
        if (status != null) {
            row.report(status, reportRev, message);
        }
        return row;
    }

    private static Result compose(AddonDefinition d, AddonState row, Boolean observed) {
        return AddonStateRules.evaluate(d, new Facts(row, observed, null, null));
    }

    @Test
    void aPlannedAddonIsNeverWantedAndNeverOn() {
        Result r = AddonStateRules.evaluate(planned(), new Facts(row(true, 1, "running", 1L, null), true, null, null));
        assertFalse(r.desired());
        assertEquals(AddonStatus.UNAVAILABLE, r.status());
    }

    // ------------------------------------------------------------------ compose

    @Test
    void untouchedAndNotRunningIsOff() {
        assertEquals(AddonStatus.OFF, compose(compose(false), null, false).status());
        assertEquals(AddonStatus.OFF, compose(compose(false), null, null).status());
        assertFalse(compose(compose(false), null, false).desired());
    }

    @Test
    void untouchedDefaultOnIsWantedAndWaitsForTheHost() {
        Result r = compose(compose(true), null, false);
        assertTrue(r.desired());
        assertEquals(AddonStatus.ENABLE_REQUESTED, r.status());
    }

    @Test
    void untouchedButSeenRunningIsAdoptedAsWantedAndOn() {
        Result r = compose(compose(false), null, true);
        assertTrue(r.desired(), "an install made before the registry must not be switched off by it");
        assertEquals(AddonStatus.ON, r.status());
    }

    @Test
    void wantedWithoutAReportWaitsForTheHost() {
        Result r = compose(compose(false), row(true, 1, null, null, null), false);
        assertEquals(AddonStatus.ENABLE_REQUESTED, r.status());
        assertTrue(r.desired());
    }

    @Test
    void aCurrentRunningReportMeansOn() {
        assertEquals(AddonStatus.ON, compose(compose(false), row(true, 1, "running", 1L, null), false).status());
    }

    @Test
    void anOldRunningReportDoesNotCountForANewWish() {
        // wish switched on (1), off (2), on again (3); the host only ever reported at revision 1
        AddonState row = row(true, 3, "running", 1L, null);
        assertEquals(AddonStatus.ENABLE_REQUESTED, compose(compose(false), row, false).status());
    }

    @Test
    void ownObservationStandsInWhenThereIsNoCurrentReport() {
        AddonState row = row(true, 2, "stopped", 1L, null);
        assertEquals(AddonStatus.ON, compose(compose(false), row, true).status());
        assertEquals(AddonStatus.ENABLE_REQUESTED, compose(compose(false), row, false).status());
    }

    @Test
    void applyingAndFailedReportsAreShownWithTheirMessage() {
        Result applying = compose(compose(false), row(true, 1, "applying", 1L, "pulling image"), false);
        assertEquals(AddonStatus.APPLYING, applying.status());
        assertEquals("pulling image", applying.message());
        Result failed = compose(compose(false), row(true, 1, "failed", 1L, "port 8888 in use"), false);
        assertEquals(AddonStatus.FAILED, failed.status());
        assertEquals("port 8888 in use", failed.message());
    }

    @Test
    void switchedOffButStillRunningWaitsForTheHostToStopIt() {
        // reported running at the old wish (rev 1), wish is now off (rev 2)
        AddonState row = row(false, 2, "running", 1L, null);
        Result r = compose(compose(false), row, null);
        assertFalse(r.desired());
        assertEquals(AddonStatus.DISABLE_REQUESTED, r.status());
        // the same, seen only by core's own observation
        assertEquals(AddonStatus.DISABLE_REQUESTED, compose(compose(false), row(false, 1, null, null, null), true).status());
        // a current report that it still runs
        assertEquals(AddonStatus.DISABLE_REQUESTED, compose(compose(false), row(false, 2, "running", 2L, null), false).status());
    }

    @Test
    void switchedOffAndStoppedIsOff() {
        assertEquals(AddonStatus.OFF, compose(compose(false), row(false, 2, "stopped", 2L, null), true).status());
        assertEquals(AddonStatus.OFF, compose(compose(true), row(false, 1, null, null, null), false).status());
    }

    @Test
    void aStoredRowAlwaysBeatsTheDefault() {
        assertFalse(compose(compose(true), row(false, 1, null, null, null), true).desired());
        assertTrue(compose(compose(false), row(true, 1, null, null, null), false).desired());
    }

    // -------------------------------------------------------------------- model

    private static Result model(AddonState row, Boolean installed, Pull pull) {
        return AddonStateRules.evaluate(model(), new Facts(row, null, installed, pull));
    }

    @Test
    void anInstalledModelNobodyAskedAboutIsAdopted() {
        Result r = model(null, true, null);
        assertTrue(r.desired());
        assertEquals(AddonStatus.ON, r.status());
    }

    @Test
    void aModelNobodyAskedAboutAndNotInstalledIsOff() {
        assertEquals(AddonStatus.OFF, model(null, false, null).status());
        assertEquals(AddonStatus.OFF, model(null, null, null).status());
    }

    @Test
    void aRunningDownloadShowsProgress() {
        Result r = model(row(true, 1, null, null, null), false, new Pull("running", 42, "pulling layer"));
        assertEquals(AddonStatus.PULLING, r.status());
        assertEquals(42, r.progress());
        assertEquals("pulling layer", r.message());
        assertEquals(AddonStatus.PULLING, model(row(true, 1, null, null, null), false, new Pull("pending", 0, "Waiting")).status());
    }

    @Test
    void aFinishedDownloadIsOn() {
        assertEquals(AddonStatus.ON, model(row(true, 1, null, null, null), true, new Pull("done", 100, "Installed")).status());
    }

    @Test
    void aFailedDownloadIsFailedWithItsMessage() {
        Result r = model(row(true, 1, null, null, null), false, new Pull("failed", 10, "disk full"));
        assertEquals(AddonStatus.FAILED, r.status());
        assertEquals("disk full", r.message());
    }

    @Test
    void wantedButMissingWithNoJobIsFailedAndSaysHowToRetry() {
        Result r = model(row(true, 1, null, null, null), false, null);
        assertEquals(AddonStatus.FAILED, r.status());
        assertTrue(r.message().contains("Turn it on again"));
    }

    @Test
    void wantedWhileTheServerIsDownWaits() {
        Result r = model(row(true, 1, null, null, null), null, null);
        assertEquals(AddonStatus.ENABLE_REQUESTED, r.status());
        assertTrue(r.message().contains("not reachable"));
    }

    @Test
    void switchedOffKeepsTheFilesAndIsOff() {
        Result r = model(row(false, 2, null, null, null), true, null);
        assertFalse(r.desired());
        assertEquals(AddonStatus.OFF, r.status());
        assertNull(r.progress());
    }
}
