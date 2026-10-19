package com.agenticai.core.agents.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.AgentResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentRelevanceSelector}'s Jev upgrade paths. The contract: Jev's
 * calibrated decisions REPLACE the lexical signal when configured (a {@code
 * choice} for the primary pick, one batched {@code noul} call for additional
 * relevant agents), and any failure or empty answer restores the exact
 * pre-Jev lexical behavior - Jev never changes the method contract, only the
 * quality of the signal.
 */
class AgentRelevanceSelectorJevTest {

    private final DecisionEngine decisions = mock(DecisionEngine.class);

    private static AgentResponse agent(String name, String prompt) {
        AgentResponse a = mock(AgentResponse.class);
        when(a.id()).thenReturn(UUID.randomUUID());
        when(a.name()).thenReturn(name);
        when(a.systemPrompt()).thenReturn(prompt);
        return a;
    }

    @Test
    void jevChoiceOverridesLexicalPick() {
        // Lexically "Support Triage" wins (more keyword overlap); Jev knows
        // the task is really a translation job and picks Translator. This is
        // the #591 failure mode Jev exists to fix.
        AgentResponse support = agent("Support Triage", "Answers customer support questions about tickets");
        AgentResponse translator = agent("Translator", "Translates text between languages");
        String translatorId = translator.id().toString();
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.choose(anyString(), anyString(), anyMap()))
            .thenReturn(Optional.of(new DecisionModel.Choice(translatorId, Map.of(), 0.9)));
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);

        AgentResponse picked = s.selectBest(
            "support ticket: translate this to Hindi please", List.of(support, translator));

        assertThat(picked).isSameAs(translator);
    }

    @Test
    void jevEmptyAnswerFallsBackToLexical() {
        AgentResponse support = agent("Support Triage", "Answers customer support questions");
        AgentResponse billing = agent("Billing Bot", "Handles invoices and payments");
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.choose(anyString(), anyString(), anyMap())).thenReturn(Optional.empty());
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);

        assertThat(s.selectBest("customer support question about tickets", List.of(billing, support)))
            .isSameAs(support);
    }

    @Test
    void jevUnavailableNeverCalled() {
        AgentResponse a = agent("A", "does a");
        AgentResponse b = agent("B", "does b");
        when(decisions.isAvailable()).thenReturn(false);
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);

        s.selectBest("do a thing", List.of(a, b));

        verify(decisions, never()).choose(anyString(), anyString(), anyMap());
    }

    @Test
    void routingDisabledNeverConsultsJev() {
        AgentResponse a = agent("A", "does a");
        AgentResponse b = agent("B", "does b");
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, false);

        s.selectBest("do a thing", List.of(a, b));

        verify(decisions, never()).isAvailable();
    }

    @Test
    void additionalRelevantUsesBatchedNoulAndHonoursThreshold() {
        AgentResponse primary = agent("Translator", "Translates text between languages");
        AgentResponse crm = agent("CRM Logger", "Logs records into the CRM system");
        AgentResponse billing = agent("Billing Bot", "Handles invoices");
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        // One batched call judges every non-primary candidate at once:
        // crm says yes, billing says no. (Ids hoisted before stubbing -
        // evaluating a mock inside thenReturn is unfinished stubbing.)
        String crmId = crm.id().toString();
        String billingId = billing.id().toString();
        when(decisions.nouls(anyString(), anyMap())).thenReturn(Map.of(crmId, 0.8, billingId, 0.2));
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);

        List<AgentResponse> extra = s.selectAdditionalRelevant(
            "translate this to Hindi and log it in the CRM",
            List.of(primary, crm, billing), primary, 5);

        assertThat(extra).containsExactly(crm);
    }

    @Test
    void additionalRelevantJevSaysNoReturnsEmptyNotLexical() {
        // A populated-but-all-below-threshold Jev answer is Jev's calibrated
        // "no" - falling back to lexical here would re-introduce the very
        // keyword-guessing the Jev call just ruled out.
        AgentResponse primary = agent("A", "alpha agent");
        AgentResponse other = agent("B", "beta agent shares some words");
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        String otherId = other.id().toString();
        when(decisions.nouls(anyString(), anyMap())).thenReturn(Map.of(otherId, 0.2));
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);

        assertThat(s.selectAdditionalRelevant("alpha words task", List.of(primary, other), primary, 5))
            .isEmpty();
    }

    @Test
    void tenantPolicyOffNeverConsultsJev() {
        // jev_policy.agent_routing=false - tenant opted out of Jev in Quick
        // Task; the lexical path runs and Jev is never asked.
        AgentResponse a = agent("A", "does a");
        AgentResponse b = agent("B", "does b");
        when(decisions.tenantPolicy()).thenReturn(
            Optional.of(new DecisionPolicyView(null, false, null)));
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);

        s.selectBest("do a thing", List.of(a, b));

        verify(decisions, never()).isAvailable();
        verify(decisions, never()).choose(anyString(), anyString(), anyMap());
    }

    @Test
    void additionalRelevantJevNoAnswerFallsBackToLexical() {
        AgentResponse primary = agent("A", "unrelated alpha thing");
        AgentResponse other = agent("CRM Logger", "Logs records into the CRM system");
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.nouls(anyString(), anyMap())).thenReturn(Map.of()); // Jev unreachable/empty
        AgentRelevanceSelector s = new AgentRelevanceSelector(decisions, true);

        List<AgentResponse> extra = s.selectAdditionalRelevant(
            "log this in the crm records", List.of(primary, other), primary, 5);

        assertThat(extra).containsExactly(other);
    }
}
