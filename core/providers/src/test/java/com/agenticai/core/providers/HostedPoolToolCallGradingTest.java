package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.HostedPoolToolCallGrading.Measurement;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link HostedPoolToolCallGrading}'s measurement semantics: the probe is optional infrastructure
 * (it lives in core/chat, which cannot be a dependency of this module), so every "could not answer"
 * path - no probe wired, an empty result, a thrown exception - collapses to {@code UNMEASURED} and
 * never fails a save.
 */
class HostedPoolToolCallGradingTest {

    @Test
    void noProbeWiredMeansUnmeasured() {
        HostedPoolToolCallGrading grading = new HostedPoolToolCallGrading(null);
        Measurement m = grading.measure("https://x.example", null, "m", "k");
        assertSame(HostedPoolToolCallGrading.UNMEASURED, m);
        assertTrue(!m.measured());
    }

    @Test
    void anEmptyProbeResultAndAThrownProbeAreBothUnmeasured() {
        ToolCallCapabilityProbe probe = mock(ToolCallCapabilityProbe.class);
        HostedPoolToolCallGrading grading = new HostedPoolToolCallGrading(probe);

        when(probe.probe(anyString(), isNull(), anyString(), isNull()))
                .thenReturn(Optional.empty());
        assertSame(
                HostedPoolToolCallGrading.UNMEASURED,
                grading.measure("https://x.example", null, "m", null));

        when(probe.probe(anyString(), isNull(), anyString(), isNull()))
                .thenThrow(new IllegalStateException("probe exploded"));
        assertSame(
                HostedPoolToolCallGrading.UNMEASURED,
                grading.measure("https://x.example", null, "m", null));
    }

    @Test
    void aNullGradeInsideAResultIsUnmeasuredToo() {
        ToolCallCapabilityProbe probe = mock(ToolCallCapabilityProbe.class);
        when(probe.probe(anyString(), isNull(), anyString(), isNull()))
                .thenReturn(
                        Optional.of(
                                new ToolCallCapabilityProbe.Result(
                                        null, ToolCallCapabilityProbe.ServerKind.OTHER, null)));
        assertSame(
                HostedPoolToolCallGrading.UNMEASURED,
                new HostedPoolToolCallGrading(probe).measure("https://x.example", null, "m", null));
    }

    @Test
    void aMeasuredResultCarriesTheGradeTheRouterModeAndTheSize() {
        ToolCallCapabilityProbe probe = mock(ToolCallCapabilityProbe.class);
        when(probe.probe(anyString(), isNull(), anyString(), isNull()))
                .thenReturn(
                        Optional.of(
                                new ToolCallCapabilityProbe.Result(
                                        ToolCallGrade.TEXT,
                                        ToolCallCapabilityProbe.ServerKind.LLAMA_CPP,
                                        7.5)));

        Measurement m =
                new HostedPoolToolCallGrading(probe).measure("https://x.example", null, "m", null);
        assertTrue(m.measured());
        assertEquals(ToolCallGrade.TEXT, m.grade());
        assertEquals(
                ToolRouterMode.FORMAT, m.routerMode(), "llama.cpp supports json_schema format");
        assertEquals(7.5, m.parameterBillions());
    }

    @Test
    void applyWritesOnlyAMeasuredResult() {
        PlatformHostedLlmProvider entity =
                new PlatformHostedLlmProvider(
                        "l", "https://x.example", "m", null, true, 0, null, null);
        HostedPoolToolCallGrading.apply(entity, HostedPoolToolCallGrading.UNMEASURED);
        assertNull(entity.getToolCallGrade());
        HostedPoolToolCallGrading.apply(entity, null);
        assertNull(entity.getToolCallGrade());

        HostedPoolToolCallGrading.apply(
                entity, new Measurement(ToolCallGrade.STRUCTURED, ToolRouterMode.REQUIRED, null));
        assertEquals(ToolCallGrade.STRUCTURED, entity.getToolCallGrade());
        assertEquals(ToolRouterMode.REQUIRED, entity.getToolRouterMode());
        assertNull(entity.getParameterBillions(), "a null size leaves the stored one alone");
    }
}
