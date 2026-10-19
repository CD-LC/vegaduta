package com.agenticai.core.agents.job.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.eventbus.WorkflowEvent;
import com.agenticai.core.eventbus.WorkflowEventSignal;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE LOOP GUARD - the mandatory protection PR-3 exists for, and the half of the
 * acceptance criterion that says a self-triggering configuration must be
 * "stopped by the guard and surface a clear diagnostic rather than a bill".
 *
 * <p><b>The failure mode being prevented is a real, unbounded bill</b>, and it
 * needs no attacker and no external party: {@code GenerateDocumentToolCallback}
 * -&gt; {@code DocumentGenerationService#requestGeneration} -&gt; {@code
 * DocumentGenerationEventListener} -&gt; {@code document.generation.completed}
 * -&gt; a trigger whose agent holds the generate-document tool -&gt; forever,
 * with a full LLM turn charged to a real human's budget on every iteration.
 *
 * <p><b>Every test here fails if the guard is deleted.</b> {@link
 * #firingPastTheCapDisablesTheTriggerAndCreatesNoFurtherJobs} in particular
 * asserts the job COUNT, not just the disabled flag - removing the guard while
 * leaving the flag would keep the flag assertion honest and let the bill through.
 */
class AgentEventTriggerLoopGuardTest {

    private static final String DOCUMENT_GENERATED = WorkflowEventTypes.DOCUMENT_GENERATION_COMPLETED;

    private AgentEventTriggerRepository repository;
    private AgentJobLauncher launcher;
    private AgentJobProperties properties;
    private MeterRegistry meterRegistry;
    private NotificationService notificationService;
    private AgentEventTriggerDispatcher dispatcher;

    /**
     * The in-memory firing ledger. The loop guard's counts are DERIVED from
     * {@code agent_event_trigger_fire} rows at firing time rather than stored on
     * the trigger (a stored counter is a lost-update bug under concurrent
     * firings - see {@code AgentEventTrigger}), so the fire-repository mock has
     * to BE a ledger: {@code saveAndFlush} appends here and the windowed count
     * counts what was appended. A plain {@code return 0} stub would switch the
     * guard off and quietly turn every test in this class into a no-op.
     */
    private final List<AgentEventTriggerFire> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(AgentEventTriggerRepository.class);
        AgentEventTriggerFireRepository fireRepository = mock(AgentEventTriggerFireRepository.class);
        launcher = mock(AgentJobLauncher.class);
        properties = new AgentJobProperties();
        meterRegistry = new SimpleMeterRegistry();
        notificationService = mock(NotificationService.class);

        when(fireRepository.saveAndFlush(any(AgentEventTriggerFire.class)))
            .thenAnswer(invocation -> {
                AgentEventTriggerFire fire = invocation.getArgument(0);
                ledger.add(fire);
                return fire;
            });
        when(fireRepository.countByTriggerIdAndFiredAtGreaterThanEqual(any(UUID.class), any(Instant.class)))
            .thenAnswer(invocation -> {
                UUID triggerId = invocation.getArgument(0);
                Instant from = invocation.getArgument(1);
                return ledger.stream()
                    .filter(fire -> fire.getTriggerId().equals(triggerId))
                    .filter(fire -> !fire.getFiredAt().isBefore(from))
                    .count();
            });
        // The TENANT-WIDE count: the whole ledger (one tenant is one schema).
        // Stubbed from the same list so the tenant caps are exercised for real -
        // a `return 0` here would silently disable the tenant half of the guard
        // in every test below.
        when(fireRepository.countByFiredAtGreaterThanEqual(any(Instant.class)))
            .thenAnswer(invocation -> {
                Instant from = invocation.getArgument(0);
                return ledger.stream()
                    .filter(fire -> !fire.getFiredAt().isBefore(from))
                    .count();
            });
        // disableIfEnabled is a GUARDED atomic UPDATE in production (mutate only
        // while still enabled, report who won through the rowcount). The mock
        // mirrors that contract onto the loaded entity, which is what lets the
        // firing's re-read see the disable and turn later events into skips.
        when(repository.disableIfEnabled(
            any(UUID.class), any(AgentEventTriggerDisableReason.class), any(Instant.class)))
            .thenAnswer(invocation -> {
                AgentEventTrigger trigger =
                    repository.findById(invocation.<UUID>getArgument(0)).orElse(null);
                if (trigger == null || !trigger.isEnabled()) {
                    return 0;
                }
                trigger.disable(invocation.getArgument(1), invocation.getArgument(2));
                return 1;
            });
        when(launcher.enqueue(any(AgentJobLaunch.class))).thenReturn(UUID.randomUUID());

        dispatcher = new AgentEventTriggerDispatcher(
            repository,
            new AgentEventTriggerFiring(repository, fireRepository, launcher, properties),
            properties,
            new ObjectMapper(),
            meterRegistry,
            notificationService,
            new AgentEventTriggerPresenceCache()
        );
        TenantContext.set(TenantContext.of(AgentEventTriggerTestSupport.TENANT, "docgen", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * The self-triggering configuration, end to end: an agent subscribed to the
     * completion event its own tool produces. Each iteration is a DISTINCT event
     * (a fresh run id), so the dedupe ledger cannot stop it - only the firing cap
     * can, which is exactly why the cap exists on top of the dedupe rather than
     * instead of it.
     */
    @Test
    @DisplayName("a self-triggering agent is stopped by the firing cap, not by a bill")
    void firingPastTheCapDisablesTheTriggerAndCreatesNoFurtherJobs() {
        AgentEventTrigger selfTriggering = AgentEventTriggerTestSupport.trigger(
            DOCUMENT_GENERATED, null, "Generate the follow-up document for {{payload.jobId}}.");
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(DOCUMENT_GENERATED))
            .thenReturn(List.of(selfTriggering));
        when(repository.findById(selfTriggering.getId())).thenReturn(Optional.of(selfTriggering));
        properties.getTrigger().setMaxFiresPerWindow(3);

        for (int i = 0; i < 10; i++) {
            dispatcher.onWorkflowEvent(new WorkflowEventSignal(
                WorkflowEvent.of(
                    DOCUMENT_GENERATED, AgentEventTriggerTestSupport.TENANT, UUID.randomUUID().toString(),
                    Map.of("jobId", UUID.randomUUID().toString(), "success", true))));
        }

        // THE assertion. Ten events, three jobs - the loop is bounded by the cap
        // and not by anyone noticing the invoice. Delete the guard and this is 10.
        verify(launcher, times(3)).enqueue(any(AgentJobLaunch.class));

        assertThat(selfTriggering.isEnabled()).isFalse();
        assertThat(selfTriggering.getDisabledReason())
            .isEqualTo(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED);
        // Tripped exactly once, not once per subsequent event: after the disable,
        // the re-read inside the firing transaction turns every later event into a
        // cheap skip rather than another trip.
        // Ten distinct events in one loop iteration land well inside the burst
        // window, so it is the BURST scope that trips (the sustained scope
        // exists for the slow drip this test deliberately does not model).
        assertThat(meterRegistry.counter("agent.job.trigger.loop_guard_tripped",
            "eventType", DOCUMENT_GENERATED, "scope", "BURST").count()).isEqualTo(1.0);
    }

    /**
     * "A clear diagnostic rather than a bill" is the acceptance criterion's exact
     * wording, and a WARN log is not a diagnostic anybody sees. The notification
     * has to name the agent (via its deep link) and say what tripped, or the
     * operator's only route to the answer is a log search they do not know to run.
     */
    @Test
    void theDiagnosticNamesTheAgentAndReachesAHuman() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.trigger(
            DOCUMENT_GENERATED, null, "React to {{payload.jobId}}.");
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(DOCUMENT_GENERATED))
            .thenReturn(List.of(trigger));
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        properties.getTrigger().setMaxFiresPerWindow(1);

        for (int i = 0; i < 3; i++) {
            dispatcher.onWorkflowEvent(new WorkflowEventSignal(
                WorkflowEvent.of(
                    DOCUMENT_GENERATED, AgentEventTriggerTestSupport.TENANT, UUID.randomUUID().toString(),
                    Map.of("jobId", "doc-1"))));
        }

        verify(notificationService, times(1)).create(
            isNull(),
            eq(NotificationCategory.SYSTEM),
            eq("Agent event trigger disabled: firing loop detected"),
            contains("disabled"),
            eq(NotificationSeverity.ERROR),
            eq("/agents/" + trigger.getAgentId()),
            isNull()
        );
    }

    /**
     * A notification-write failure must not roll back the disable that already
     * stopped the loop. The disable is committed by the time the notification is
     * attempted, which is precisely why it is attempted OUTSIDE the firing
     * transaction.
     */
    @Test
    void aBrokenNotificationDoesNotUndoTheDisable() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.trigger(DOCUMENT_GENERATED, null, "React.");
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(DOCUMENT_GENERATED))
            .thenReturn(List.of(trigger));
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        when(notificationService.create(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("notification table is unavailable"));
        properties.getTrigger().setMaxFiresPerWindow(1);

        for (int i = 0; i < 2; i++) {
            dispatcher.onWorkflowEvent(new WorkflowEventSignal(
                WorkflowEvent.of(
                    DOCUMENT_GENERATED, AgentEventTriggerTestSupport.TENANT, UUID.randomUUID().toString(),
                    Map.of("jobId", "doc-1"))));
        }

        assertThat(trigger.isEnabled()).isFalse();
        verify(launcher, times(1)).enqueue(any(AgentJobLaunch.class));
    }

    /**
     * The window is ROLLING, not lifetime - and in the ledger design that is a
     * property of the QUERY, not of any stored counter: both guard counts are
     * "rows with {@code fired_at >= now - window}", so history simply ages out
     * of the count instead of being reset by anybody. A trigger that
     * legitimately fired many times long ago must never accumulate its way into
     * being disabled - that would make the guard indistinguishable from a
     * lifetime quota, and operators would learn to raise it until it stopped
     * meaning anything.
     */
    @Test
    void firesOutsideTheWindowAgeOutRatherThanAccumulating() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.trigger(
            DOCUMENT_GENERATED, null, "React to {{payload.jobId}}.");
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(DOCUMENT_GENERATED))
            .thenReturn(List.of(trigger));
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        properties.getTrigger().setMaxFiresPerWindow(3);

        // A busy history, all of it comfortably outside BOTH windows (two days
        // is past the 24h sustained window too). Under the first cut's tumbling
        // counter this history is exactly what would have wrongly disabled a
        // healthy trigger.
        Instant longAgo = Instant.now().minus(Duration.ofDays(2));
        for (int i = 0; i < 10; i++) {
            ledger.add(AgentEventTriggerFire.claim(
                UUID.randomUUID(), trigger.getId(), "old|" + i, null, longAgo));
        }

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(
            WorkflowEvent.of(
                DOCUMENT_GENERATED, AgentEventTriggerTestSupport.TENANT, UUID.randomUUID().toString(),
                Map.of("jobId", UUID.randomUUID().toString()))));

        // The old fires aged out of the count, so this delivery is fire #1
        // against the cap, not fire #11: the job exists and the trigger is on.
        verify(launcher, times(1)).enqueue(any(AgentJobLaunch.class));
        assertThat(trigger.isEnabled()).isTrue();
    }

    /**
     * Re-saving a trigger is the ONLY way back from a loop-guard disable, and it
     * has to restart the counting: the ledger rows that tripped the guard are
     * still there (nothing prunes them), so without a floor the very next event
     * would count straight back over the cap and the operator would report the
     * feature as broken rather than the loop as unfixed. {@code reconfigure}
     * stamps {@code guard_reset_at}, and every guard count starts no earlier
     * than that stamp.
     */
    @Test
    void reconfiguringRestartsTheCountingSoTheTriggerIsActuallyUsableAgain() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.trigger(
            DOCUMENT_GENERATED, null, "React to {{payload.jobId}}.");
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(DOCUMENT_GENERATED))
            .thenReturn(List.of(trigger));
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        properties.getTrigger().setMaxFiresPerWindow(3);

        // The loop: five fires inside the burst window, guard tripped, disabled.
        Instant tripping = Instant.now().minusSeconds(10);
        for (int i = 0; i < 5; i++) {
            ledger.add(AgentEventTriggerFire.claim(
                UUID.randomUUID(), trigger.getId(), "loop|" + i, null, tripping));
        }
        trigger.disable(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED, Instant.now());

        // The operator fixes the cycle and re-saves.
        trigger.reconfigure(null, "React to the message, once.", Instant.now());
        assertThat(trigger.isEnabled()).isTrue();
        assertThat(trigger.getDisabledReason()).isNull();
        assertThat(trigger.getGuardResetAt()).isNotNull();

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(
            WorkflowEvent.of(
                DOCUMENT_GENERATED, AgentEventTriggerTestSupport.TENANT, UUID.randomUUID().toString(),
                Map.of("jobId", UUID.randomUUID().toString()))));

        // Counting starts from guard_reset_at, so this is fire #1 against the
        // cap, not fire #6: the job is created and the trigger STAYS enabled.
        verify(launcher, times(1)).enqueue(any(AgentJobLaunch.class));
        assertThat(trigger.isEnabled()).isTrue();
    }

    /**
     * THE SLOW LOOP - the exact cycle the guard was originally written for and
     * the burst window provably cannot see (one document-generation iteration
     * costs an LLM turn plus a render, slower than one fire per 15s). The
     * SUSTAINED cap is what catches it; delete that cap and this test buys ten
     * jobs instead of three.
     */
    @Test
    @DisplayName("a loop slower than the burst window is stopped by the sustained cap")
    void aSlowLoopUnderTheBurstCapIsStoppedByTheSustainedCap() {
        AgentEventTrigger selfTriggering = AgentEventTriggerTestSupport.trigger(
            DOCUMENT_GENERATED, null, "Generate the follow-up document for {{payload.jobId}}.");
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(DOCUMENT_GENERATED))
            .thenReturn(List.of(selfTriggering));
        when(repository.findById(selfTriggering.getId())).thenReturn(Optional.of(selfTriggering));
        // A burst cap the slow loop never reaches - which is exactly the shipped
        // shape at defaults (cycle time > window/cap) - and a tight sustained cap.
        properties.getTrigger().setMaxFiresPerWindow(1000);
        properties.getTrigger().setMaxFiresPerSustainedWindow(3);

        for (int i = 0; i < 10; i++) {
            dispatcher.onWorkflowEvent(new WorkflowEventSignal(
                WorkflowEvent.of(
                    DOCUMENT_GENERATED, AgentEventTriggerTestSupport.TENANT, UUID.randomUUID().toString(),
                    Map.of("jobId", UUID.randomUUID().toString(), "success", true))));
        }

        verify(launcher, times(3)).enqueue(any(AgentJobLaunch.class));
        assertThat(selfTriggering.isEnabled()).isFalse();
        assertThat(selfTriggering.getDisabledReason())
            .isEqualTo(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED);
        assertThat(meterRegistry.counter("agent.job.trigger.loop_guard_tripped",
            "eventType", DOCUMENT_GENERATED, "scope", "SUSTAINED").count()).isEqualTo(1.0);
    }

    /**
     * THE FAN-OUT - many triggers, each comfortably inside its own per-trigger
     * budget, collectively burning money. No per-trigger cap can see this
     * shape; the TENANT-WIDE ceiling is the only bound it answers to. Delete
     * the tenant cap and this test buys twenty jobs instead of four.
     */
    @Test
    @DisplayName("a fan-out under every per-trigger cap is stopped by the tenant-wide ceiling")
    void aFanOutUnderPerTriggerCapsIsStoppedByTheTenantWideCeiling() {
        AgentEventTrigger first = AgentEventTriggerTestSupport.trigger(
            DOCUMENT_GENERATED, null, "Follow up on {{payload.jobId}}.");
        AgentEventTrigger second = AgentEventTriggerTestSupport.trigger(
            DOCUMENT_GENERATED, null, "Also follow up on {{payload.jobId}}.");
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(DOCUMENT_GENERATED))
            .thenReturn(List.of(first, second));
        when(repository.findById(first.getId())).thenReturn(Optional.of(first));
        when(repository.findById(second.getId())).thenReturn(Optional.of(second));
        // Per-trigger caps far away; the tenant burst ceiling is the bound.
        properties.getTrigger().setMaxFiresPerWindow(1000);
        properties.getTrigger().setMaxFiresPerSustainedWindow(10000);
        properties.getTrigger().setMaxTenantFiresPerWindow(4);

        for (int i = 0; i < 10; i++) {
            dispatcher.onWorkflowEvent(new WorkflowEventSignal(
                WorkflowEvent.of(
                    DOCUMENT_GENERATED, AgentEventTriggerTestSupport.TENANT, UUID.randomUUID().toString(),
                    Map.of("jobId", UUID.randomUUID().toString(), "success", true))));
        }

        // Two triggers x ten events = twenty candidate firings; the tenant
        // ceiling of four is what actually bounds the spend.
        verify(launcher, times(4)).enqueue(any(AgentJobLaunch.class));
        // Both crossed the tenant line as they kept firing, and each got the
        // same disable + diagnostic a per-trigger trip gets.
        assertThat(first.isEnabled()).isFalse();
        assertThat(second.isEnabled()).isFalse();
        assertThat(first.getDisabledReason()).isEqualTo(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED);
        assertThat(second.getDisabledReason()).isEqualTo(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED);
        assertThat(meterRegistry.counter("agent.job.trigger.loop_guard_tripped",
            "eventType", DOCUMENT_GENERATED, "scope", "TENANT_BURST").count()).isEqualTo(2.0);
    }
}
