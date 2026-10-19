package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.guardrails.ToolDispatchGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * End to end through the dispatch decision, with the shipped classifications and the shipped default
 * exempt list: a booking agent can search free slots unattended, but moving or cancelling a customer's
 * appointment parks for a human. Pins the reason the booking writes are separate tool names rather
 * than more actions on the confirmation-exempt {@code google_calendar}.
 */
class AgentToolGovernanceBookingToolsTest {

    /** The default of {@code app.tools.classification.confirmation-gate-exempt-tools} in AgentToolGovernance. */
    private static final String SHIPPED_EXEMPT_LIST =
            "channel_send_message,run_code,run_in_code_workspace,browser_click,browser_type,google_gmail,google_calendar";

    private final ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);

    private ToolDispatchGate.Outcome outcome(String toolName) {
        when(registry.resolve(toolName))
                .thenReturn(new ToolClassificationDecision.Classified(BuiltInToolClassifications.all().get(toolName)));
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf((MeterRegistry) new SimpleMeterRegistry()),
                        providerOf(null),
                        true,
                        SHIPPED_EXEMPT_LIST);
        return governance.decide(registry, toolName).outcome();
    }

    @Test
    void freeSlotSearchRunsWithoutAHuman() {
        assertEquals(ToolDispatchGate.Outcome.ALLOW, outcome("google_calendar_find_free_slots"));
    }

    @Test
    void movingOrCancellingAnAppointmentWaitsForApproval() {
        assertEquals(ToolDispatchGate.Outcome.REQUIRE_APPROVAL, outcome("google_calendar_move_event"));
        assertEquals(ToolDispatchGate.Outcome.REQUIRE_APPROVAL, outcome("google_calendar_cancel_event"));
    }

    @Test
    void theOlderMultiActionCalendarToolIsStillExemptByDefault() {
        // Known and deliberate for now: google_calendar (list_upcoming + create_event) stays on the
        // exempt list so existing scheduled agents do not stall. Removing it from
        // TOOL_CONFIRMATION_GATE_EXEMPT gates create_event invites too. If that default changes,
        // update this test and docs/connectors/google-calendar-sheets.md together.
        assertEquals(ToolDispatchGate.Outcome.ALLOW, outcome("google_calendar"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
