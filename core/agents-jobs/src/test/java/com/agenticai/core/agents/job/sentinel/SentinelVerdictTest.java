package com.agenticai.core.agents.job.sentinel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The verdict contract a sentinel-mode run must end with - pure parsing, no Spring. */
class SentinelVerdictTest {

    @Test
    void quietVerdictParses() {
        var v = SentinelVerdict.extract("All clear.\n\n```sentinel\n{\"verdict\":\"quiet\"}\n```");
        assertTrue(v.isPresent());
        assertEquals(SentinelVerdict.Verdict.QUIET, v.get().verdict());
        assertEquals(SentinelVerdict.Severity.INFO, v.get().severity());
    }

    @Test
    void notifyVerdictCarriesTitleBodySeverity() {
        var v = SentinelVerdict.extract("""
            Found something.

            ```sentinel
            {"verdict":"notify","title":"3 failed runs","body":"The nightly ETL tripped.","severity":"WARNING"}
            ```
            """);
        assertTrue(v.isPresent());
        assertEquals(SentinelVerdict.Verdict.NOTIFY, v.get().verdict());
        assertEquals("3 failed runs", v.get().title());
        assertEquals("The nightly ETL tripped.", v.get().body());
        assertEquals(SentinelVerdict.Severity.WARNING, v.get().severity());
    }

    @Test
    void proposeVerdictCarriesProposal() {
        var v = SentinelVerdict.extract("""
            ```sentinel
            {"verdict":"propose","title":"Disk nearly full","severity":"ERROR",
             "proposal":{"summary":"Rotate logs","goal":"Compress and rotate /var/log/*"}}
            ```
            """);
        assertTrue(v.isPresent());
        assertEquals(SentinelVerdict.Verdict.PROPOSE, v.get().verdict());
        assertEquals("Rotate logs", v.get().proposal().summary());
        assertEquals("Compress and rotate /var/log/*", v.get().proposal().goal());
    }

    @Test
    void proposeWithoutUsableProposalDowngradesToNotify() {
        // The signal still reaches the human, just without the one-tap action.
        var v = SentinelVerdict.extract(
            "```sentinel\n{\"verdict\":\"propose\",\"title\":\"x\"}\n```");
        assertTrue(v.isPresent());
        assertEquals(SentinelVerdict.Verdict.NOTIFY, v.get().verdict());
        assertNull(v.get().proposal());
    }

    @Test
    void lastBlockWinsOverEarlierDraft() {
        var v = SentinelVerdict.extract(
            "```sentinel\n{\"verdict\":\"notify\",\"title\":\"draft\"}\n```\n"
                + "rethinking...\n"
                + "```sentinel\n{\"verdict\":\"quiet\"}\n```");
        assertTrue(v.isPresent());
        assertEquals(SentinelVerdict.Verdict.QUIET, v.get().verdict());
    }

    @Test
    void missingOrMalformedBlocksYieldEmpty() {
        assertTrue(SentinelVerdict.extract(null).isEmpty());
        assertTrue(SentinelVerdict.extract("").isEmpty());
        assertTrue(SentinelVerdict.extract("no block here").isEmpty());
        assertTrue(SentinelVerdict.extract("```sentinel\n{not json}\n```").isEmpty());
        assertTrue(SentinelVerdict.extract("```sentinel\n{\"verdict\":\"shout\"}\n```").isEmpty());
    }

    @Test
    void longFieldsAreClampedNotRejected() {
        String longTitle = "t".repeat(500);
        var v = SentinelVerdict.extract(
            "```sentinel\n{\"verdict\":\"notify\",\"title\":\"" + longTitle + "\"}\n```");
        assertTrue(v.isPresent());
        assertEquals(200, v.get().title().length());
    }
}
