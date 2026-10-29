package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.addons.AddonDefinition.Compose;
import com.agenticai.core.providers.addons.AddonDefinition.Observe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AddonDefinition} is the file's contract (the catalog row) and {@link AddonState} the
 * stored wish + last host report. The rules pinned here: null collections normalize to empty
 * immutable lists, {@code reportedRev} must match the current {@code rev} for a report to count as
 * current (a stale report about an older wish says nothing), and {@code rev} increments on every
 * change of the wish.
 */
class AddonDefinitionAndStateTest {

    private static AddonDefinition definition(AddonDefinition.Availability availability) {
        return new AddonDefinition(
                "search",
                "Search",
                "Web search",
                AddonDefinition.Category.SEARCH,
                AddonDefinition.Kind.COMPOSE_PROFILE,
                availability,
                500,
                1024,
                false,
                "MIT",
                true,
                null,
                false,
                "vegaduta personal --profile search",
                "docs/addons.md#search",
                null,
                null,
                null,
                null);
    }

    @Test
    void nullCollectionsNormalizeToEmptyImmutableLists() {
        AddonDefinition d = definition(AddonDefinition.Availability.AVAILABLE);
        assertEquals(List.of(), d.requires());
        assertEquals(List.of(), d.unlocks());
        assertThrows(UnsupportedOperationException.class, () -> d.requires().add("x"));
        assertTrue(d.isAvailable());
        assertFalse(definition(AddonDefinition.Availability.PLANNED).isAvailable());
    }

    @Test
    void unlockKeysAreStableWireStringsAndKnownRejectsUnknownOnes() {
        assertEquals("web_search", AddonDefinition.Unlock.WEB_SEARCH.wire());
        assertEquals("local_models", AddonDefinition.Unlock.LOCAL_MODELS.wire());
        assertTrue(AddonDefinition.Unlock.known("speech"));
        assertFalse(AddonDefinition.Unlock.known("does_not_exist"));
        assertFalse(AddonDefinition.Unlock.known("WEB_SEARCH"), "wire keys are exact, lower-case");
    }

    @Test
    void composeNormalizesNullsAndReportsEmptiness() {
        Compose empty = new Compose(null, null, null);
        assertTrue(empty.isEmpty());
        Compose profilesOnly = new Compose(List.of("search"), null, null);
        assertFalse(profilesOnly.isEmpty());
        assertEquals(List.of("search"), profilesOnly.profiles());
        Compose envOnly = new Compose(null, null, Map.of("FEATURE_X", "true"));
        assertFalse(envOnly.isEmpty());
        assertEquals(Map.of("FEATURE_X", "true"), envOnly.env());
        assertThrows(UnsupportedOperationException.class, () -> envOnly.env().put("Y", "1"));
    }

    @Test
    void observeCarriesTheTwoTestNamesAsConstants() {
        assertEquals("non-blank", Observe.NON_BLANK);
        assertEquals("true", Observe.TRUE);
        Observe o = new Observe("app.features.search", Observe.TRUE);
        assertEquals("app.features.search", o.property());
        assertEquals("true", o.test());
    }

    // --- AddonState -----------------------------------------------------------

    @Test
    void aFreshRowHasNoReportSoNothingIsCurrent() {
        AddonState state = new AddonState("search", true, 1, "admin");
        assertTrue(state.isDesired());
        assertEquals(1, state.getRev());
        assertFalse(state.reportIsCurrent());
        assertNull(state.getReportedStatus());
        assertNull(state.getReportedRev());
    }

    @Test
    void aReportAgainstTheCurrentRevCountsAsCurrent() {
        AddonState state = new AddonState("search", true, 3, "admin");
        state.report(AddonState.RUNNING, 3, "up");
        assertTrue(state.reportIsCurrent());
        assertEquals(AddonState.RUNNING, state.getReportedStatus());
        assertEquals(3L, state.getReportedRev());
        assertEquals("up", state.getReportedMessage());
        assertTrue(state.getReportedAt() != null);
    }

    @Test
    void aReportAgainstAnOlderRevSaysNothingAboutTheCurrentWish() {
        AddonState state = new AddonState("search", true, 1, "admin");
        state.report(AddonState.RUNNING, 1, null);
        state.change(false, "admin");
        // The wish moved to rev 2; the rev-1 report no longer applies.
        assertFalse(state.isDesired());
        assertEquals(2, state.getRev());
        assertFalse(state.reportIsCurrent());
    }

    @Test
    void aNullReportedRevNeverCountsAsCurrent() {
        AddonState state = new AddonState("search", true, 1, "admin");
        assertFalse(state.reportIsCurrent());
        state.report(AddonState.FAILED, 1, "boom");
        assertTrue(state.reportIsCurrent());
        assertEquals(AddonState.FAILED, state.getReportedStatus());
        assertEquals("boom", state.getReportedMessage());
    }
}
