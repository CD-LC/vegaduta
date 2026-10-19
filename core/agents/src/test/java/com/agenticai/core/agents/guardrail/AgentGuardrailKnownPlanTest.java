package com.agenticai.core.agents.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.ResourceAccessService;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The inbound guardrail check reuses the plan the turn already resolved instead of reading it
 * again.
 */
class AgentGuardrailKnownPlanTest {

    private final TenantLimits limits = mock(TenantLimits.class);
    private final AgentGuardrailService service =
            new AgentGuardrailService(
                    limits, mock(AgentRepository.class), mock(ResourceAccessService.class));
    private final Set<String> configured = Set.of("profanity", "pii", "toxicity");

    @Test
    void aKnownPlanGatesTheTypesWithoutReadingThePlan() {
        Set<ContentGuardrailType> premium =
                service.resolveEnforceableGuardrails(configured, "acme", TenantPlan.PREMIUM);
        Set<ContentGuardrailType> free =
                service.resolveEnforceableGuardrails(configured, "acme", TenantPlan.FREE);

        assertThat(premium)
                .containsExactlyInAnyOrder(
                        ContentGuardrailType.PROFANITY, ContentGuardrailType.PII);
        assertThat(free).containsExactly(ContentGuardrailType.PROFANITY);
        verifyNoInteractions(limits);
    }

    @Test
    void withoutAKnownPlanItIsReadOnceAndGivesTheSameAnswer() {
        when(limits.plan("acme")).thenReturn(TenantPlan.PREMIUM);

        Set<ContentGuardrailType> read = service.resolveEnforceableGuardrails(configured, "acme");

        assertThat(read)
                .isEqualTo(
                        service.resolveEnforceableGuardrails(
                                configured, "acme", TenantPlan.PREMIUM));
        verify(limits, times(1)).plan("acme");
    }
}
