package com.agenticai.core.common.studioapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.common.studioapp.StudioProjectRules.VersionIn;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudioProjectRulesTest {

    private static VersionIn v(long at, String html) {
        return new VersionIn(at, "note", "device", html);
    }

    @Test
    void idsMustBeShortAndSafe() {
        assertTrue(StudioProjectRules.validId("sa-mg3k2j-x9ab"));
        assertFalse(StudioProjectRules.validId(null));
        assertFalse(StudioProjectRules.validId(""));
        assertFalse(StudioProjectRules.validId("../etc"));
        assertFalse(StudioProjectRules.validId("a".repeat(65)));
    }

    @Test
    void sanitizeDropsBlanksDedupesByTimestampAndOrdersOldestFirst() {
        List<VersionIn> out = StudioProjectRules.sanitize(List.of(
            v(30, "<c>"), v(10, "<a>"), v(20, " "), v(0, "<zero>"), v(10, "<a2>"), new VersionIn(40, "n", "device", null)));
        assertEquals(List.of(10L, 30L), out.stream().map(VersionIn::at).toList());
        assertEquals("<a2>", out.get(0).html(), "last write wins for the same timestamp");
    }

    @Test
    void sanitizeNormalisesUnknownSourceAndClipsNote() {
        VersionIn out = StudioProjectRules.sanitize(List.of(
            new VersionIn(5, "x".repeat(1000), "bogus", "<p>"))).get(0);
        assertEquals("manual", out.source());
        assertEquals(StudioProjectRules.MAX_NOTE_CHARS, out.note().length());
        assertEquals("publish", StudioProjectRules.sanitize(List.of(
            new VersionIn(5, null, "publish", "<p>"))).get(0).source());
    }

    @Test
    void sanitizeKeepsOnlyTheNewestVersions() {
        List<VersionIn> many = new ArrayList<>();
        for (int i = 1; i <= StudioProjectRules.MAX_VERSIONS + 7; i++) {
            many.add(v(i, "<v" + i + ">"));
        }
        List<VersionIn> out = StudioProjectRules.sanitize(many);
        assertEquals(StudioProjectRules.MAX_VERSIONS, out.size());
        assertEquals(8L, out.get(0).at());
        assertEquals(StudioProjectRules.MAX_VERSIONS + 7L, out.get(out.size() - 1).at());
    }

    @Test
    void oversizedVersionAndPayloadAreRejected() {
        String huge = "a".repeat(StudioProjectRules.MAX_HTML_CHARS + 1);
        assertEquals("version_too_large",
            assertThrows(IllegalArgumentException.class, () -> StudioProjectRules.sanitize(List.of(v(1, huge)))).getMessage());

        String big = "a".repeat(StudioProjectRules.MAX_HTML_CHARS);
        List<VersionIn> nine = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            nine.add(v(i, big));
        }
        assertEquals("payload_too_large",
            assertThrows(IllegalArgumentException.class, () -> StudioProjectRules.sanitize(nine)).getMessage());
    }

    @Test
    void nullOrEmptyIncomingIsEmpty() {
        assertTrue(StudioProjectRules.sanitize(null).isEmpty());
        assertTrue(StudioProjectRules.sanitize(List.of()).isEmpty());
    }

    @Test
    void surplusIsTheOldestBeyondTheCap() {
        List<Long> ats = new ArrayList<>();
        for (long i = StudioProjectRules.MAX_VERSIONS + 3; i >= 1; i--) {
            ats.add(i); // deliberately unsorted (descending)
        }
        assertEquals(List.of(1L, 2L, 3L), StudioProjectRules.surplus(ats));
        assertTrue(StudioProjectRules.surplus(List.of(1L, 2L)).isEmpty());
    }
}
