package com.agenticai.core.agents.job.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.trigger.dto.AgentEventTriggerRequest;
import com.agenticai.core.agents.job.trigger.dto.AgentEventTriggerResponse;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The CONFIGURATION half: everything that can be rejected is rejected while the
 * operator is still looking at the form.
 *
 * <p>That posture is the deliberate difference from {@code
 * AgentScheduleService#renderGoal}, which leaves an unknown placeholder LITERAL
 * so the typo shows up in the job's goal. A schedule has three possible
 * placeholders and fires within a day; a trigger's placeholder set depends on
 * which of nine event types it is bound to, and its next firing may be weeks away
 * and unattended. A trigger that can never match is otherwise indistinguishable
 * from a feature that does not work.
 */
class AgentEventTriggerServiceTest {

    private static final String WHATSAPP = WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED;

    private AgentEventTriggerRepository repository;
    private AgentJobProperties properties;
    private com.agenticai.core.security.TenantLimits tenantLimits;
    private AgentEventTriggerService service;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        repository = mock(AgentEventTriggerRepository.class);
        properties = new AgentJobProperties();
        agentId = UUID.randomUUID();
        // The automation plan-gate: this suite acts as a tenant that HAS the
        // entitlement; the dedicated denial case stubs it off.
        tenantLimits = mock(com.agenticai.core.security.TenantLimits.class);
        when(tenantLimits.automationAllowed(any())).thenReturn(true);
        service = new AgentEventTriggerService(
            repository, mock(AgentService.class), properties, new ObjectMapper(),
            new AgentEventTriggerPresenceCache(), tenantLimits);
        when(repository.save(any(AgentEventTrigger.class))).thenAnswer(i -> i.getArgument(0));
        // Every firing runs with the configuring human's identity, so configuring
        // one without an authenticated context is not a thing that can happen.
        TenantContext.set(TenantContext.of("t_test", AgentEventTriggerTestSupport.CREATED_BY, Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AgentEventTriggerResponse upsert(String eventType, String filterJson, String goalTemplate) {
        return service.upsert(agentId, new AgentEventTriggerRequest(eventType, filterJson, goalTemplate));
    }

    @Test
    void anEventTypeThePlatformDoesNotPublishIsRejectedWithTheValidSet() {
        assertThatThrownBy(() -> upsert("customer.churned", null, "React."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customer.churned")
            // Event names are not invented here; the nine real ones have to be in
            // the message because they are not guessable.
            .hasMessageContaining(WHATSAPP)
            .hasMessageContaining(WorkflowEventTypes.DOCUMENT_GENERATION_COMPLETED);
        verify(repository, never()).save(any(AgentEventTrigger.class));
    }

    @Test
    void aFilterKeyTheEventDoesNotCarryIsRejectedRatherThanSilentlyNeverMatching() {
        assertThatThrownBy(() -> upsert(WHATSAPP, "{\"sender\":\"+911234\"}", "React."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sender")
            .hasMessageContaining("fromMasked");
    }

    @Test
    void aFilterThatIsNotAFlatJsonObjectIsRejected() {
        assertThatThrownBy(() -> upsert(WHATSAPP, "not json at all", "React."))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> upsert(WHATSAPP, "{\"channel\":{\"eq\":\"whatsapp\"}}", "React."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nested objects and arrays are not supported");
    }

    @Test
    void aGoalTemplateReferencingAFieldTheEventDoesNotCarryIsRejected() {
        assertThatThrownBy(() -> upsert(WHATSAPP, null, "Summarise {{payload.body}}."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload.body");
    }

    @Test
    void aGoalTemplateOverTheCharacterCapIsRejectedBeforeInterpolationEatsIt() {
        properties.getTrigger().setMaxGoalChars(100);

        assertThatThrownBy(() -> upsert(WHATSAPP, null, "x".repeat(101)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max-goal-chars");
    }

    @Test
    void aValidTriggerIsStoredWithTheConfiguringHumanAsItsAuthority() {
        AgentEventTriggerResponse response =
            upsert(WHATSAPP, "{\"channel\":\"whatsapp\"}", "Triage the message from {{payload.fromMasked}}.");

        ArgumentCaptor<AgentEventTrigger> saved = ArgumentCaptor.forClass(AgentEventTrigger.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getAgentId()).isEqualTo(agentId);
        assertThat(saved.getValue().getEventType()).isEqualTo(WHATSAPP);
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(AgentEventTriggerTestSupport.CREATED_BY);
        assertThat(saved.getValue().isEnabled()).isTrue();
        // Served so the console's template help comes from the same source the
        // validator uses, instead of a client-side copy that drifts.
        assertThat(response.availablePlaceholders()).contains("payload.fromMasked", "occurredAt");
    }

    @Test
    void rebindingTheSameEventTypeEditsTheExistingRowRatherThanAddingASecond() {
        AgentEventTrigger existing = AgentEventTrigger.create(
            UUID.randomUUID(), agentId, WHATSAPP, null, "Old goal.",
            AgentEventTriggerTestSupport.CREATED_BY, Instant.now());
        when(repository.findByAgentIdAndEventType(agentId, WHATSAPP)).thenReturn(Optional.of(existing));

        upsert(WHATSAPP, null, "New goal.");

        // Two subscriptions of one agent to one event type would double every
        // firing for any event both filters accept - the opposite of "exactly one
        // job per event".
        assertThat(existing.getGoalTemplate()).isEqualTo("New goal.");
        verify(repository, never()).countByAgentIdAndEnabledTrue(any(UUID.class));
    }

    @Test
    void reSavingIsTheWayBackFromALoopGuardDisable() {
        AgentEventTrigger disabled = AgentEventTrigger.create(
            UUID.randomUUID(), agentId, WHATSAPP, null, "Old goal.",
            AgentEventTriggerTestSupport.CREATED_BY, Instant.now());
        disabled.disable(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED, Instant.now());
        when(repository.findByAgentIdAndEventType(agentId, WHATSAPP)).thenReturn(Optional.of(disabled));

        AgentEventTriggerResponse response = upsert(WHATSAPP, null, "A goal that does not loop.");

        assertThat(response.enabled()).isTrue();
        assertThat(response.disabledReason()).isNull();
        // The counting has to restart too, or the very next event re-disables it
        // and the operator reports the feature as broken rather than the loop as
        // unfixed. The guard's counts are derived from the firing ledger rather
        // than stored on the trigger, so "restart" means guard_reset_at is
        // stamped: every count starts no earlier than it, which keeps the next
        // firing from walking back into the rows that tripped the guard.
        assertThat(response.guardResetAt()).isNotNull();
    }

    @Test
    void theEnabledTriggerCapIsEnforcedOnCreateOnly() {
        properties.getTrigger().setMaxTriggersPerAgent(2);
        when(repository.countByAgentIdAndEnabledTrue(agentId)).thenReturn(2L);

        assertThatThrownBy(() -> upsert(WHATSAPP, null, "React."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximum of 2");
    }

    @Test
    void disableKeepsTheConfigurationSoReEnablingIsOnePut() {
        AgentEventTrigger trigger = AgentEventTrigger.create(
            UUID.randomUUID(), agentId, WHATSAPP, "{\"channel\":\"whatsapp\"}", "React.",
            AgentEventTriggerTestSupport.CREATED_BY, Instant.now());
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));

        AgentEventTriggerResponse response = service.disable(agentId, trigger.getId());

        assertThat(response.enabled()).isFalse();
        assertThat(response.disabledReason()).isEqualTo(AgentEventTriggerDisableReason.OPERATOR.name());
        assertThat(response.goalTemplate()).isEqualTo("React.");
        assertThat(response.filterJson()).isEqualTo("{\"channel\":\"whatsapp\"}");
    }

    @Test
    void aTriggerBelongingToAnotherAgentIsNotFoundRatherThanDisabled() {
        AgentEventTrigger someoneElses = AgentEventTrigger.create(
            UUID.randomUUID(), UUID.randomUUID(), WHATSAPP, null, "React.",
            AgentEventTriggerTestSupport.CREATED_BY, Instant.now());
        when(repository.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));

        // The access check is on the AGENT in the path, so a trigger id from a
        // different agent must not be reachable through it.
        assertThatThrownBy(() -> service.disable(agentId, someoneElses.getId()))
            .isInstanceOf(NoSuchElementException.class);
        assertThat(someoneElses.isEnabled()).isTrue();
    }

    @Test
    void listReadsEveryTriggerForTheAgentIncludingDisabledOnes() {
        AgentEventTrigger enabled = AgentEventTriggerTestSupport.trigger(WHATSAPP, null, "React.");
        AgentEventTrigger off = AgentEventTriggerTestSupport.trigger(
            WorkflowEventTypes.CHANNEL_EMAIL_MESSAGE_RECEIVED, null, "React.");
        off.disable(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED, Instant.now());
        when(repository.findByAgentIdOrderByEventTypeAsc(agentId)).thenReturn(List.of(enabled, off));

        List<AgentEventTriggerResponse> triggers = service.list(agentId);

        // A disabled trigger is the interesting one - hiding it is how an operator
        // concludes their subscription was deleted rather than switched off.
        assertThat(triggers).hasSize(2);
        assertThat(triggers.get(1).disabledReason())
            .isEqualTo(AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED.name());
    }

    @Test
    void anEventTypeIsAcceptedWithSurroundingWhitespace() {
        // Copy-pasted from the docs with a trailing space is not a configuration
        // error worth a 400.
        assertThat(upsert("  " + WHATSAPP + "  ", null, "React.").eventType()).isEqualTo(WHATSAPP);
    }

    @Test
    void everyPublishedEventTypeIsSubscribable() {
        // Regression lock on the allow-list: if a constant is added to
        // WorkflowEventTypes and not here, an operator cannot subscribe to it and
        // the only symptom is a 400 that looks like their mistake.
        assertThat(AgentTriggerEventTypes.subscribable()).hasSize(11);
        for (String eventType : AgentTriggerEventTypes.subscribable()) {
            assertThat(AgentTriggerEventTypes.payloadFieldsOf(eventType)).isNotEmpty();
            assertThat(upsert(eventType, null, "React to {{eventType}}.").eventType()).isEqualTo(eventType);
        }
    }

    @Test
    void aStoredFilterThatNoLongerParsesFailsLoudlyRatherThanBecomingNoFilter() {
        // Only reachable by hand-editing a row past requireUsable. A filter that
        // cannot be evaluated must not degrade into "match everything" - that
        // would turn a narrow subscription into a firehose.
        assertThatThrownBy(() -> AgentEventTriggerFilter.parse("{oops", new ObjectMapper()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filterMatchingIsCaseInsensitiveOnTheValueSideOnly() {
        // Publishers write Boolean, Integer and String for what an operator thinks
        // of as one value, so "TRUE" not matching true would be a filter that
        // looks right and never fires.
        assertThat(AgentEventTriggerFilter.matches(
            Map.of("success", "TRUE"), Map.of("success", Boolean.TRUE))).isTrue();
        assertThat(AgentEventTriggerFilter.matches(
            Map.of("bodyLength", "42"), Map.of("bodyLength", 42))).isTrue();
        assertThat(AgentEventTriggerFilter.matches(
            Map.of("Channel", "whatsapp"), Map.of("channel", "whatsapp"))).isFalse();
    }

    @Test
    void configuringWithoutAnAuthenticatedContextIsRefused() {
        TenantContext.clear();

        assertThatThrownBy(() -> upsert(WHATSAPP, null, "React."))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tenant context");
    }

    @Test
    void anUnknownEventTypeNeverReachesTheRepository() {
        assertThatThrownBy(() -> upsert("workflow.node.completed", null, "React."))
            .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findByAgentIdAndEventType(any(UUID.class), anyString());
    }
}
