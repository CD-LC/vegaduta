package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Wait node planning: resume-time computation for every mode, the duration cap, parsing and save-time lint. */
class WorkflowWaitServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration CAP = Duration.ofDays(7);

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static WorkflowWaitService.Plan plan(Map<String, Object> config) {
        return WorkflowWaitService.computePlan(config, NOW, CAP);
    }

    // ---- duration mode ------------------------------------------------------------------------------------------

    @Test
    void durationWithUnitComputesTheWakeTime() {
        WorkflowWaitService.Plan p = plan(cfg("mode", "duration", "duration", 90, "unit", "minutes"));
        assertEquals(WorkflowWaitService.Mode.DURATION, p.mode());
        assertEquals(NOW.plus(Duration.ofMinutes(90)), p.wakeAt());
        assertFalse(p.immediate());
        assertTrue(p.summary().contains("1h 30m"), p.summary());
    }

    @Test
    void durationAcceptsShorthandAndIsoForms() {
        assertEquals(NOW.plusSeconds(30), plan(cfg("duration", "30s")).wakeAt());
        assertEquals(NOW.plus(Duration.ofHours(2)), plan(cfg("duration", "2h")).wakeAt());
        assertEquals(NOW.plus(Duration.ofHours(36)), plan(cfg("duration", "1.5d")).wakeAt());
        assertEquals(NOW.plus(Duration.ofMinutes(45)), plan(cfg("duration", "PT45M")).wakeAt());
        assertEquals(NOW.plus(Duration.ofDays(2)), plan(cfg("duration", "2", "unit", "days")).wakeAt());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), plan(cfg("duration", "5")).wakeAt(), "default unit is minutes");
    }

    @Test
    void aZeroDurationDoesNotParkTheRun() {
        WorkflowWaitService.Plan p = plan(cfg("duration", "0"));
        assertTrue(p.immediate());
    }

    @Test
    void durationAtExactlyTheCapIsAllowedAndOneSecondOverIsRefused() {
        assertEquals(NOW.plus(CAP), plan(cfg("duration", "7d")).wakeAt());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> plan(cfg("duration", "604801s")));
        assertTrue(e.getMessage().contains("exceeds the maximum of 7d"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("duration", "30d")));
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("duration", "P8D")));
    }

    @Test
    void theCapIsConfigurable() {
        Duration threeDays = Duration.ofDays(3);
        assertEquals(NOW.plus(Duration.ofDays(3)), WorkflowWaitService.computePlan(cfg("duration", "3d"), NOW, threeDays).wakeAt());
        assertThrows(IllegalArgumentException.class,
            () -> WorkflowWaitService.computePlan(cfg("duration", "4d"), NOW, threeDays));
    }

    @Test
    void badDurationsAreClearErrors() {
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("mode", "duration")));
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("duration", "soon")));
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("duration", "-5m")));
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("duration", "5 fortnights")));
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("duration", "PXYZ")));
    }

    // ---- until mode ---------------------------------------------------------------------------------------------

    @Test
    void untilWaitsForAFutureInstant() {
        WorkflowWaitService.Plan p = plan(cfg("mode", "until", "until", "2026-10-05T09:00:00Z"));
        assertEquals(WorkflowWaitService.Mode.UNTIL, p.mode());
        assertEquals(Instant.parse("2026-10-05T09:00:00Z"), p.wakeAt());
        assertFalse(p.immediate());
    }

    @Test
    void untilAcceptsOffsetsZonelessAndBareDatesAndEpochs() {
        assertEquals(Instant.parse("2026-10-05T03:30:00Z"), plan(cfg("until", "2026-10-05T09:00:00+05:30")).wakeAt());
        assertEquals(Instant.parse("2026-10-05T09:00:00Z"), plan(cfg("until", "2026-10-05T09:00:00")).wakeAt());
        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), plan(cfg("until", "2026-10-06")).wakeAt());
        assertEquals(Instant.ofEpochSecond(1_791_000_000L), WorkflowWaitService.parseInstant("1791000000"));
        assertEquals(Instant.ofEpochMilli(1_791_000_000_123L), WorkflowWaitService.parseInstant("1791000000123"));
    }

    @Test
    void untilInThePastDoesNotPark() {
        WorkflowWaitService.Plan p = plan(cfg("until", "2026-10-01T00:00:00Z"));
        assertTrue(p.immediate());
        assertTrue(p.summary().contains("already passed"), p.summary());
    }

    @Test
    void untilBeyondTheCapIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> plan(cfg("until", "2026-12-01T00:00:00Z")));
        assertTrue(e.getMessage().contains("exceeds the maximum"), e.getMessage());
    }

    @Test
    void anUnparseableUntilIsAClearError() {
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("mode", "until", "until", "next tuesday")));
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("mode", "until")));
    }

    @Test
    void modeIsInferredFromTheConfigWhenAbsent() {
        assertEquals(WorkflowWaitService.Mode.UNTIL, plan(cfg("until", "2026-10-05T09:00:00Z")).mode());
        assertEquals(WorkflowWaitService.Mode.DURATION, plan(cfg("duration", "5m")).mode());
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("mode", "sometimes", "duration", "5m")));
    }

    // ---- webhook mode -------------------------------------------------------------------------------------------

    @Test
    void webhookModeDefaultsItsTimeoutToTheCapAndFailsOnTimeout() {
        WorkflowWaitService.Plan p = plan(cfg("mode", "webhook"));
        assertEquals(WorkflowWaitService.Mode.WEBHOOK, p.mode());
        assertEquals(NOW.plus(CAP), p.wakeAt());
        assertTrue(p.failOnTimeout());
    }

    @Test
    void webhookTimeoutAndOnTimeoutAreHonouredAndCapped() {
        WorkflowWaitService.Plan p = plan(cfg("mode", "webhook", "timeout", "12", "timeoutUnit", "hours", "onTimeout", "continue"));
        assertEquals(NOW.plus(Duration.ofHours(12)), p.wakeAt());
        assertFalse(p.failOnTimeout());
        assertThrows(IllegalArgumentException.class, () -> plan(cfg("mode", "webhook", "timeout", "30d")));
    }

    @Test
    void webhookModeWithoutAResumeUrlBaseRefusesToPark() {
        WorkflowWaitService unconfigured = new WorkflowWaitService(
            mock(WorkflowWaitRepository.class), new WorkflowWaitResumeTokens("", ""), true, 7);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> unconfigured.plan(node(cfg("mode", "webhook")), NOW, RunMode.PRODUCTION));
        assertTrue(e.getMessage().contains("public-base-url"), e.getMessage());

        WorkflowWaitService configured = new WorkflowWaitService(
            mock(WorkflowWaitRepository.class), new WorkflowWaitResumeTokens("secret", "https://api.example.com"), true, 7);
        assertFalse(configured.plan(node(cfg("mode", "webhook")), NOW, RunMode.PRODUCTION).immediate());
    }

    // ---- service-level behaviour --------------------------------------------------------------------------------

    private static WorkflowNodeDto node(Map<String, Object> config) {
        return new WorkflowNodeDto("w1", "wait", Map.of(), Map.of("config", config, "label", "Wait"));
    }

    @Test
    void aDryRunSkipsWaitingAndSaysSo() {
        WorkflowWaitService svc = new WorkflowWaitService(
            mock(WorkflowWaitRepository.class), new WorkflowWaitResumeTokens("", ""), true, 7);
        WorkflowWaitService.Plan p = svc.plan(node(cfg("duration", "2d")), NOW, RunMode.DRAFT_DRY_RUN);
        assertTrue(p.immediate());
        assertTrue(p.summary().startsWith("DRY RUN - "), p.summary());
    }

    @Test
    void aDraftRunStillParksForReal() {
        WorkflowWaitService svc = new WorkflowWaitService(
            mock(WorkflowWaitRepository.class), new WorkflowWaitResumeTokens("", ""), true, 7);
        assertFalse(svc.plan(node(cfg("duration", "2d")), NOW, RunMode.DRAFT).immediate());
    }

    @Test
    void theServiceCapComesFromTheConfiguredDays() {
        WorkflowWaitService svc = new WorkflowWaitService(
            mock(WorkflowWaitRepository.class), new WorkflowWaitResumeTokens("", ""), true, 2);
        assertEquals(Duration.ofDays(2), svc.maxWait());
        assertThrows(IllegalArgumentException.class, () -> svc.plan(node(cfg("duration", "3d")), NOW, RunMode.PRODUCTION));
        assertTrue(svc.isEnabled());
    }

    @Test
    void registerWritesOneDurableRowForTheParkedRun() throws Exception {
        WorkflowWaitRepository repository = mock(WorkflowWaitRepository.class);
        WorkflowWaitService svc = new WorkflowWaitService(repository, new WorkflowWaitResumeTokens("", ""), true, 7);
        WorkflowDefinition definition = new WorkflowDefinition("wf", "d", "{}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        UUID workflowId = UUID.randomUUID();
        idField.set(definition, workflowId);
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), workflowId, "wf", "u");
        WorkflowWaitService.Plan p = plan(cfg("duration", "2h"));

        svc.register(run, definition, node(cfg("duration", "2h")), "next-node", p);

        ArgumentCaptor<WorkflowWait> saved = ArgumentCaptor.forClass(WorkflowWait.class);
        verify(repository).save(saved.capture());
        WorkflowWait row = saved.getValue();
        assertEquals(run.getId(), row.getRunId());
        assertEquals(workflowId, row.getWorkflowId());
        assertEquals("w1", row.getNodeId());
        assertEquals("next-node", row.getResumeNodeId());
        assertEquals("DURATION", row.getMode());
        assertEquals(NOW.plus(Duration.ofHours(2)), row.getWakeAt());
        assertEquals(WorkflowWait.Status.WAITING, row.getStatus());
        verify(repository).retireLiveForRun(eq(run.getId()), eq(WorkflowWait.Status.WAITING),
            eq(WorkflowWait.Status.CANCELLED), any(Instant.class));
    }

    // ---- save-time lint -----------------------------------------------------------------------------------------

    @Test
    void validateConfigFlagsMissingAndInvalidLiteralsButAcceptsExpressions() {
        List<String> errors = new ArrayList<>();
        WorkflowWaitService.validateConfig(cfg("mode", "duration"), "W", CAP, errors);
        WorkflowWaitService.validateConfig(cfg("mode", "until"), "W", CAP, errors);
        WorkflowWaitService.validateConfig(cfg("duration", "30d"), "W", CAP, errors);
        WorkflowWaitService.validateConfig(cfg("until", "garbage"), "W", CAP, errors);
        WorkflowWaitService.validateConfig(cfg("mode", "nope"), "W", CAP, errors);
        assertEquals(5, errors.size(), errors.toString());

        List<String> ok = new ArrayList<>();
        WorkflowWaitService.validateConfig(cfg("duration", "2h"), "W", CAP, ok);
        WorkflowWaitService.validateConfig(cfg("until", "{{ $trigger.when }}"), "W", CAP, ok);
        WorkflowWaitService.validateConfig(cfg("duration", "{{ $trigger.minutes }}"), "W", CAP, ok);
        WorkflowWaitService.validateConfig(cfg("mode", "webhook"), "W", CAP, ok);
        assertTrue(ok.isEmpty(), ok.toString());
    }

    @Test
    void humanizeReadsNaturally() {
        assertEquals("2h", WorkflowWaitService.humanize(Duration.ofHours(2)));
        assertEquals("1d 12h", WorkflowWaitService.humanize(Duration.ofHours(36)));
        assertEquals("45s", WorkflowWaitService.humanize(Duration.ofSeconds(45)));
        assertEquals("250 ms", WorkflowWaitService.humanize(Duration.ofMillis(250)));
    }
}
