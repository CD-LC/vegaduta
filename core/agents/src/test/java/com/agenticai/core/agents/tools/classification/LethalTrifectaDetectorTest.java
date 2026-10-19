package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LethalTrifectaDetectorTest {

    private static ToolClassificationDecision classified(
        String name, ToolTrustTier trust, ToolSensitivity sensitivity, boolean irreversible
    ) {
        return new ToolClassificationDecision.Classified(new ToolClassification(
            name, trust, sensitivity, ToolDeterminism.SIDE_EFFECT, null, irreversible, true));
    }

    @Test
    void allThreeLegsAcrossSeparateToolsIsLethal() {
        Map<String, ToolClassificationDecision> d = new LinkedHashMap<>();
        d.put("read_crm", classified("read_crm", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT, false));
        d.put("fetch_web_page",
            classified("fetch_web_page", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_PUBLIC, false));
        d.put("send_mail", classified("send_mail", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, true));

        LethalTrifectaDetector.Report report = LethalTrifectaDetector.analyseDecisions(d);

        assertTrue(report.isLethalTrifecta());
        assertEquals(List.of("read_crm"), report.privateDataTools());
        assertEquals(List.of("fetch_web_page"), report.untrustedContentTools());
        assertEquals(List.of("send_mail"), report.egressTools());
        assertNotNull(report.warning());
        assertTrue(report.warning().contains("send_mail"));
    }

    @Test
    void oneToolCanSupplyAllLegs() {
        Map<String, ToolClassificationDecision> d = Map.of(
            "kb", classified("kb", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_TENANT, false),
            "gmail", classified("gmail", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.EGRESS, true));

        assertTrue(LethalTrifectaDetector.analyseDecisions(d).isLethalTrifecta());
    }

    @Test
    void twoLegsAreNotLethal() {
        Map<String, ToolClassificationDecision> d = Map.of(
            "read_crm", classified("read_crm", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT, false),
            "send_mail", classified("send_mail", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, true));

        LethalTrifectaDetector.Report report = LethalTrifectaDetector.analyseDecisions(d);

        assertFalse(report.isLethalTrifecta());
        assertNull(report.warning());
    }

    @Test
    void unclassifiedToolsAreReportedNotAssumedSafeOrDangerous() {
        Map<String, ToolClassificationDecision> d = Map.of(
            "mystery", new ToolClassificationDecision.Blocked(
                "mystery", ToolClassificationDecision.Reason.UNCLASSIFIED, "no row"));

        LethalTrifectaDetector.Report report = LethalTrifectaDetector.analyseDecisions(d);

        assertEquals(List.of("mystery"), report.unclassifiedTools());
        assertFalse(report.isLethalTrifecta());
    }

    @Test
    void egressOnlyToolsAreNotAnUntrustedIngestionLeg() {
        Map<String, ToolClassificationDecision> d = Map.of(
            "read_crm", classified("read_crm", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT, false),
            "run_code", classified("run_code", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.EGRESS, false));

        LethalTrifectaDetector.Report report = LethalTrifectaDetector.analyseDecisions(d);

        assertTrue(report.untrustedContentTools().isEmpty());
        assertFalse(report.isLethalTrifecta());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> metersOf(MeterRegistry meters) {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(meters);
        return p;
    }

    private static Map<String, ToolClassificationDecision> lethalSet() {
        Map<String, ToolClassificationDecision> d = new LinkedHashMap<>();
        d.put("read_crm", classified("read_crm", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT, false));
        d.put("fetch_web_page",
            classified("fetch_web_page", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_PUBLIC, false));
        d.put("send_mail", classified("send_mail", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, true));
        return d;
    }

    @Test
    void analyseResolvesThroughTheRegistry() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        List<String> names = List.of("read_crm", "fetch_web_page", "send_mail");
        when(registry.resolveAll(names)).thenReturn(lethalSet());
        LethalTrifectaDetector detector = new LethalTrifectaDetector(registry, metersOf(null));

        assertTrue(detector.analyse(names).isLethalTrifecta());
        assertFalse(detector.analyse(List.of()).isLethalTrifecta());
        assertFalse(detector.analyse(null).isLethalTrifecta());
    }

    @Test
    void analyseNeverThrowsWhenTheRegistryFails() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        when(registry.resolveAll(anyCollection())).thenThrow(new IllegalStateException("db down"));
        LethalTrifectaDetector detector = new LethalTrifectaDetector(registry, metersOf(null));

        assertFalse(detector.analyse(List.of("x")).isLethalTrifecta());
    }

    @Test
    void warnIfLethalReturnsTheWarningAndCountsIt() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        List<String> names = List.of("read_crm", "fetch_web_page", "send_mail");
        when(registry.resolveAll(names)).thenReturn(lethalSet());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        LethalTrifectaDetector detector = new LethalTrifectaDetector(registry, metersOf(meters));

        assertNotNull(detector.warnIfLethal("agent-1", names));
        assertEquals(1.0, meters.counter(LethalTrifectaDetector.DETECTED_METRIC).count());
    }

    @Test
    void warnIfLethalIsNullAndUncountedWhenNotLethal() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        List<String> names = List.of("read_crm");
        when(registry.resolveAll(names)).thenReturn(Map.of(
            "read_crm", classified("read_crm", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT, false)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        LethalTrifectaDetector detector = new LethalTrifectaDetector(registry, metersOf(meters));

        assertNull(detector.warnIfLethal("agent-1", names));
        assertEquals(0.0, meters.counter(LethalTrifectaDetector.DETECTED_METRIC).count());
    }

    @Test
    void analyseStrictLetsAClassificationFailureThroughSoACallerCanFailSafe() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        when(registry.resolveAll(anyCollection())).thenThrow(new IllegalStateException("db down"));
        LethalTrifectaDetector detector = new LethalTrifectaDetector(registry, metersOf(null));

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> detector.analyseStrict(List.of("x")));
        assertFalse(detector.analyseStrict(List.of()).isLethalTrifecta());
        assertFalse(detector.analyseStrict(null).isLethalTrifecta());
    }

    @Test
    void recordIfLethalCountsAReportTheCallerAlreadyComputed() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        LethalTrifectaDetector detector = new LethalTrifectaDetector(mock(ToolClassificationRegistry.class), metersOf(meters));

        assertNotNull(detector.recordIfLethal("agent-1", LethalTrifectaDetector.analyseDecisions(lethalSet())));
        assertNull(detector.recordIfLethal("agent-1", LethalTrifectaDetector.analyseDecisions(Map.of())));
        assertNull(detector.recordIfLethal("agent-1", null));
        assertEquals(1.0, meters.counter(LethalTrifectaDetector.DETECTED_METRIC).count());
    }

    @Test
    void emptyInputIsHarmless() {
        assertFalse(LethalTrifectaDetector.analyseDecisions(Map.of()).isLethalTrifecta());
    }
}
