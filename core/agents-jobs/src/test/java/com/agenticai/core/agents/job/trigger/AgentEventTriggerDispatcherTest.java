package com.agenticai.core.agents.job.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.eventbus.WorkflowEvent;
import com.agenticai.core.eventbus.WorkflowEventSignal;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The acceptance criterion for PR-3's first half: <b>an event produces EXACTLY
 * ONE job</b> - once, even when the pipe delivers it twice, and never at all
 * when the event cannot be attributed to a real tenant.
 *
 * <p>{@link AgentEventTriggerFiring} is the REAL object here rather than a mock.
 * Everything interesting about a firing - the claim-before-guard ordering, the
 * identity threaded onto the launch, the re-read that respects a
 * just-disabled trigger - lives in it, and a mocked seam would assert only that
 * the dispatcher calls something.
 */
class AgentEventTriggerDispatcherTest {

    private AgentEventTriggerRepository repository;
    private AgentEventTriggerFireRepository fireRepository;
    private AgentJobLauncher launcher;
    private AgentJobProperties properties;
    private MeterRegistry meterRegistry;
    private AgentEventTriggerDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = mock(AgentEventTriggerRepository.class);
        fireRepository = mock(AgentEventTriggerFireRepository.class);
        launcher = mock(AgentJobLauncher.class);
        properties = new AgentJobProperties();
        meterRegistry = new SimpleMeterRegistry();

        when(fireRepository.saveAndFlush(any(AgentEventTriggerFire.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(launcher.enqueue(any(AgentJobLaunch.class))).thenReturn(UUID.randomUUID());

        dispatcher = new AgentEventTriggerDispatcher(
            repository,
            new AgentEventTriggerFiring(repository, fireRepository, launcher, properties),
            properties,
            new ObjectMapper(),
            meterRegistry,
            mock(NotificationService.class),
            new AgentEventTriggerPresenceCache()
        );

        // The signal is published synchronously from a thread that already holds
        // the tenant context - that is the whole contract of WorkflowEventSignal,
        // and the dispatcher refuses to read anything without it.
        TenantContext.set(TenantContext.of(
            AgentEventTriggerTestSupport.TENANT, "webhook", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void register(AgentEventTrigger trigger) {
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(trigger.getEventType()))
            .thenReturn(List.of(trigger));
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
    }

    @Test
    void oneEventWithOneMatchingTriggerCreatesExactlyOneJob() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);
        WorkflowEvent event = AgentEventTriggerTestSupport.whatsappEvent();

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(event));

        ArgumentCaptor<AgentJobLaunch> launch = ArgumentCaptor.forClass(AgentJobLaunch.class);
        verify(launcher, times(1)).enqueue(launch.capture());

        assertThat(launch.getValue().agentId()).isEqualTo(trigger.getAgentId());
        assertThat(launch.getValue().origin()).isEqualTo(AgentJobOrigin.EVENT);
        // origin_ref is the event key, so "who asked for this job?" is answerable
        // from the job row alone even after the trigger is reconfigured.
        assertThat(launch.getValue().originRef())
            .isEqualTo(AgentEventTriggerTestSupport.WHATSAPP + "|" + event.runId());
        assertThat(launch.getValue().triggerId()).isEqualTo(trigger.getId());
        // Reserved, always zero - there is no lineage signal that survives to the
        // publishing thread, and this must not look like working depth tracking.
        assertThat(launch.getValue().triggerDepth()).isZero();

        // A triggered job runs with the CONFIGURING HUMAN's frozen authority, and
        // with no roles - never an elevated "system" identity, never a budget
        // nobody owns.
        assertThat(launch.getValue().requestedBy()).isEqualTo(AgentEventTriggerTestSupport.CREATED_BY);
        assertThat(launch.getValue().subjectKey()).isEqualTo(AgentEventTriggerTestSupport.CREATED_BY);
        assertThat(launch.getValue().requestedByRoles()).isEmpty();

        ArgumentCaptor<AgentEventTriggerFire> claim = ArgumentCaptor.forClass(AgentEventTriggerFire.class);
        verify(fireRepository).saveAndFlush(claim.capture());
        assertThat(claim.getValue().getEventKey())
            .isEqualTo(AgentEventTriggerTestSupport.WHATSAPP + "|" + event.runId());
        // fire_count/last_fired_at are written by ONE atomic SQL UPDATE
        // (AgentEventTriggerRepository#recordFired), never by mutating the loaded
        // entity - so the honest assertion is on that call, not on this detached
        // instance, whose in-memory counter deliberately stays untouched.
        verify(repository).recordFired(eq(trigger.getId()), any(Instant.class));
    }

    @Test
    void theSameEventDeliveredTwiceCreatesOnlyOneJob() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);
        WorkflowEvent event = AgentEventTriggerTestSupport.whatsappEvent();

        // The pipe is at-least-once: a webhook provider retries, a poller re-reads
        // the same message. V210's UNIQUE (trigger_id, event_key) is what makes
        // the second delivery lose - modelled here as the constraint violation
        // Postgres raises on the flush.
        when(fireRepository.saveAndFlush(any(AgentEventTriggerFire.class)))
            .thenAnswer(invocation -> invocation.getArgument(0))
            .thenThrow(new DataIntegrityViolationException("uq_agent_event_trigger_fire"));

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(event));
        // The redelivery must not escape onto the publishing thread either: a
        // constraint violation reaching the webhook handler would break the
        // WhatsApp integration the retry belongs to.
        assertThatCode(() -> dispatcher.onWorkflowEvent(new WorkflowEventSignal(event)))
            .doesNotThrowAnyException();

        verify(launcher, times(1)).enqueue(any(AgentJobLaunch.class));
        // The `by` tag says WHICH dedupe absorbed it: this redelivery lost on
        // the unique index (EVENT_KEY), not on the windowed payload fingerprint.
        assertThat(meterRegistry.counter("agent.job.trigger.duplicate",
            "eventType", AgentEventTriggerTestSupport.WHATSAPP, "by", "EVENT_KEY").count()).isEqualTo(1.0);
        // Only the delivery that actually claimed the event counts as a firing -
        // otherwise a provider retrying twenty times would trip the loop guard on
        // a perfectly healthy trigger. The loop guard counts LEDGER rows, and the
        // rejected redelivery's row was never inserted (the constraint made the
        // flush lose), so asserting recordFired ran exactly once is the same
        // statement in the current design: one claim, one firing.
        verify(repository, times(1)).recordFired(eq(trigger.getId()), any(Instant.class));
    }

    @Test
    void anEventPublishedWithoutATenantContextCreatesNothing() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);

        // publish() falls back to the 'public' schema when the publishing thread
        // had no context. Dispatching there would read the empty public schema at
        // best and cross tenants at worst.
        dispatcher.onWorkflowEvent(new WorkflowEventSignal(WorkflowEvent.of(
            AgentEventTriggerTestSupport.WHATSAPP, TenantSchemaResolver.DEFAULT_SCHEMA,
            UUID.randomUUID().toString(), Map.of("channel", "whatsapp"))));

        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
        verify(repository, never()).findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(anyString());
    }

    @Test
    void withNoTenantContextOnThisThreadNothingIsRead() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);
        TenantContext.clear();

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        verify(repository, never()).findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(anyString());
        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
    }

    @Test
    void theDispatcherNeverThrowsOutOfPublish() {
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(anyString()))
            .thenThrow(new IllegalStateException("connection reset"));

        // WorkflowEventPublisher swallows what a listener throws, so a throwing
        // listener is silently useless rather than loudly broken. Failing here
        // instead is what keeps that from happening quietly.
        assertThatCode(() -> dispatcher.onWorkflowEvent(
            new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent())))
            .doesNotThrowAnyException();
        assertThat(meterRegistry.counter("agent.job.trigger.dispatch_error").count()).isEqualTo(1.0);
    }

    @Test
    void anEventTypeNobodyMaySubscribeToCostsNoQuery() {
        // The workflow engine publishes ~7 further literals whose runId repeats
        // across a whole run. They are not on the allow-list, and rejecting them
        // before any I/O is what keeps this listener cheap on a webhook thread.
        dispatcher.onWorkflowEvent(new WorkflowEventSignal(WorkflowEvent.of(
            "workflow.node.completed", AgentEventTriggerTestSupport.TENANT,
            UUID.randomUUID().toString(), Map.of())));

        verify(repository, never()).findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(anyString());
        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
    }

    @Test
    void anEventWithNoRunIdIsNotDispatchedBecauseThereIsNothingToDeduplicateOn() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(WorkflowEvent.of(
            AgentEventTriggerTestSupport.WHATSAPP, AgentEventTriggerTestSupport.TENANT, null,
            Map.of("channel", "whatsapp"))));

        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
    }

    @Test
    void aFilterThatDoesNotMatchClaimsNothingAndFiresNothing() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.trigger(
            AgentEventTriggerTestSupport.WHATSAPP, "{\"channel\":\"telegram\"}", "React.");
        register(trigger);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
        // The event key must NOT be consumed by a trigger that did not fire -
        // otherwise a non-matching trigger would poison its own ledger.
        verify(fireRepository, never()).saveAndFlush(any(AgentEventTriggerFire.class));
    }

    @Test
    void aFilterOnAFieldTheEventDoesNotCarryDoesNotMatch() {
        // A restriction whose question cannot be answered resolves to "do not
        // fire", never to "fire anyway".
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.trigger(
            WorkflowEventTypes.DOCUMENT_GENERATION_COMPLETED, "{\"success\":\"true\"}", "Follow up.");
        register(trigger);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(WorkflowEvent.of(
            WorkflowEventTypes.DOCUMENT_GENERATION_COMPLETED, AgentEventTriggerTestSupport.TENANT,
            UUID.randomUUID().toString(), Map.of("jobId", UUID.randomUUID().toString()))));

        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
    }

    @Test
    void theMasterSwitchStopsEveryFiringWithoutStoppingTheEventItself() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);
        properties.getTrigger().setEnabled(false);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
        verify(repository, never()).findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(anyString());
    }

    @Test
    void oneEventFansOutToAtMostMaxTriggersPerEvent() {
        AgentEventTrigger first = AgentEventTriggerTestSupport.whatsappTrigger();
        AgentEventTrigger second = AgentEventTriggerTestSupport.whatsappTrigger();
        AgentEventTrigger third = AgentEventTriggerTestSupport.whatsappTrigger();
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(
            AgentEventTriggerTestSupport.WHATSAPP)).thenReturn(List.of(first, second, third));
        when(repository.findById(first.getId())).thenReturn(Optional.of(first));
        when(repository.findById(second.getId())).thenReturn(Optional.of(second));
        when(repository.findById(third.getId())).thenReturn(Optional.of(third));
        // The work one inbound message may do on the thread Twilio is waiting on
        // is a hard cap, not advice.
        properties.getTrigger().setMaxTriggersPerEvent(2);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        verify(launcher, times(2)).enqueue(any(AgentJobLaunch.class));
    }

    @Test
    void aTriggerDisabledBetweenTheReadAndTheFiringDoesNotFire() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);
        // The dispatcher matched a copy read microseconds ago; another thread has
        // since tripped the loop guard. The re-read inside the firing transaction
        // is what makes "disabled" actually stop the next event.
        trigger.disable(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED, Instant.now());

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
        verify(fireRepository, never()).saveAndFlush(any(AgentEventTriggerFire.class));
    }

    /**
     * B3's honest half for the eight channel event types: their {@code runId}
     * is a FRESH random UUID per delivery ({@code publishNewRun}), so a
     * provider retry produces a different event key and the unique index can
     * never see it. The windowed payload fingerprint is what absorbs it - and
     * the {@code by=PAYLOAD} tag is how an operator tells the two guards apart.
     */
    @Test
    void aChannelRedeliveryWithAFreshRunIdIsAbsorbedByThePayloadFingerprint() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);
        // Same payload, DIFFERENT runIds - exactly what a webhook retry looks
        // like after the controller re-mints the run id.
        WorkflowEvent first = AgentEventTriggerTestSupport.whatsappEvent(UUID.randomUUID().toString());
        WorkflowEvent redelivery = AgentEventTriggerTestSupport.whatsappEvent(UUID.randomUUID().toString());
        // The fingerprint EXISTS check: false for the first delivery, true once
        // its row is in the ledger.
        when(fireRepository.existsByTriggerIdAndPayloadFingerprintAndFiredAtGreaterThanEqual(
            eq(trigger.getId()), anyString(), any(Instant.class)))
            .thenReturn(false)
            .thenReturn(true);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(first));
        dispatcher.onWorkflowEvent(new WorkflowEventSignal(redelivery));

        // One job, not two - and the second delivery never even claimed a row.
        verify(launcher, times(1)).enqueue(any(AgentJobLaunch.class));
        verify(fireRepository, times(1)).saveAndFlush(any(AgentEventTriggerFire.class));
        assertThat(meterRegistry.counter("agent.job.trigger.duplicate",
            "eventType", AgentEventTriggerTestSupport.WHATSAPP, "by", "PAYLOAD").count()).isEqualTo(1.0);
    }

    /**
     * The tenancy guard and the tenancy ROUTING must read the same source of
     * truth. The repository read below routes by the THREAD's TenantContext
     * (Hibernate's schema resolver), while the event carries its own tenantId -
     * they agree today only because publish() derived one from the other. A
     * carried event re-published onto another tenant's thread must be refused,
     * not dispatched into the wrong schema.
     */
    @Test
    void anEventCarryingADifferentTenantThanTheDispatchingThreadIsRefused() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);
        TenantContext.set(TenantContext.of("t_other", "webhook", Set.of()));

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        verify(repository, never()).findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(anyString());
        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
        assertThat(meterRegistry.counter("agent.job.trigger.dispatch_error").count()).isEqualTo(1.0);
    }

    /**
     * The hot-path concession, minimised: a tenant with no triggers for an
     * event type pays the indexed SELECT once per cache TTL, not once per
     * inbound message. Only the EMPTY answer is cached - see the second half,
     * which proves a match is never served stale.
     */
    @Test
    void aTenantWithNoTriggersPaysTheDispatchQueryOncePerTtlNotPerMessage() {
        when(repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(
            AgentEventTriggerTestSupport.WHATSAPP)).thenReturn(List.of());

        for (int i = 0; i < 5; i++) {
            dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));
        }

        verify(repository, times(1))
            .findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(AgentEventTriggerTestSupport.WHATSAPP);
    }

    @Test
    void aTenantWithATriggerIsNeverServedFromTheCache() {
        AgentEventTrigger trigger = AgentEventTriggerTestSupport.whatsappTrigger();
        register(trigger);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));
        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        // A non-empty answer is never cached: both events hit the live query
        // and both fired.
        verify(repository, times(2))
            .findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(AgentEventTriggerTestSupport.WHATSAPP);
        verify(launcher, times(2)).enqueue(any(AgentJobLaunch.class));
    }

    @Test
    void aTriggerWithNoConfiguringHumanFiresNothing() {
        AgentEventTrigger trigger = AgentEventTrigger.create(
            UUID.randomUUID(), UUID.randomUUID(), AgentEventTriggerTestSupport.WHATSAPP, null,
            "React.", null, Instant.now());
        register(trigger);

        dispatcher.onWorkflowEvent(new WorkflowEventSignal(AgentEventTriggerTestSupport.whatsappEvent()));

        // There is no identity to run the job with or bill it to, and inventing a
        // "system" one would create an agent that can act with more authority than
        // any person.
        verify(launcher, never()).enqueue(any(AgentJobLaunch.class));
    }
}
