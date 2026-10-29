package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link TrifectaStatusResponse#of} flattens a {@link LethalTrifectaDetector.Report} plus the
 * configured mode into the console-facing body. These pin the field mapping and that the warning
 * text travels verbatim (or stays null when the trifecta is not present).
 */
class TrifectaStatusResponseTest {

    @Test
    void ofFlattensTheReportAndAddsTheEnforcementFields() {
        LethalTrifectaDetector.Report report =
                new LethalTrifectaDetector.Report(
                        List.of("read_file"),
                        List.of("web_fetch"),
                        List.of("send_email"),
                        List.of("odd_tool"));

        TrifectaStatusResponse response =
                TrifectaStatusResponse.of(
                        report, TrifectaEnforcementMode.REQUIRE_APPROVAL, true, true);

        assertEquals(List.of("read_file"), response.privateDataTools());
        assertEquals(List.of("web_fetch"), response.untrustedContentTools());
        assertEquals(List.of("send_email"), response.egressTools());
        assertEquals(List.of("odd_tool"), response.unclassifiedTools());
        assertTrue(response.lethalTrifecta());
        assertEquals(report.warning(), response.warning());
        assertTrue(response.warning().contains("read_file"));
        assertEquals("require-approval", response.enforcement());
        assertTrue(response.enforced());
        assertTrue(response.gated());
    }

    @Test
    void aNonLethalReportHasNoWarning() {
        LethalTrifectaDetector.Report report =
                new LethalTrifectaDetector.Report(
                        List.of("read_file"), List.of(), List.of("send_email"), List.of());

        TrifectaStatusResponse response =
                TrifectaStatusResponse.of(report, TrifectaEnforcementMode.WARN, false, false);

        assertFalse(response.lethalTrifecta());
        assertNull(response.warning());
        assertEquals("warn", response.enforcement());
        assertFalse(response.enforced());
        assertFalse(response.gated());
    }
}
