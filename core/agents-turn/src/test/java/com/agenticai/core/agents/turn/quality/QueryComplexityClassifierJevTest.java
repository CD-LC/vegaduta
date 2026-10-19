package com.agenticai.core.agents.turn.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.decision.DecisionEngine;
import com.agenticai.core.agents.decision.DecisionPolicyView;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link QueryComplexityClassifier}'s decision-model second-opinion path. The contract:
 * the heuristic still owns "simple" outright (the decision model is never asked to agree -
 * the cheap case keeps zero added latency), the model only gets to RESCUE a
 * heuristic "not simple", and only when its calibrated probability clears the
 * configured threshold. Any model failure = the heuristic answer stands.
 */
class QueryComplexityClassifierJevTest {

    private final DecisionEngine decisions = mock(DecisionEngine.class);

    private static final String SIMPLE_MSG = "what are your hours?";
    // Long enough + a signal word ("analyze") to guarantee the heuristic
    // says NOT simple, so the decision-model path is the one under test.
    private static final String COMPLEX_LOOKING_MSG =
        "please analyze whether this vendor email is worth replying to " + "x".repeat(200);

    @Test
    void heuristicSimpleNeverConsultsJev() {
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(SIMPLE_MSG)).isTrue();
        verifyNoInteractions(decisions);
    }

    @Test
    void jevConfidentYesRescuesHeuristicFalseNegative() {
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.noul(anyString(), anyString())).thenReturn(Optional.of(0.9));
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isTrue();
    }

    @Test
    void jevBelowThresholdKeepsHeuristicVerdict() {
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.noul(anyString(), anyString())).thenReturn(Optional.of(0.5));
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isFalse();
    }

    @Test
    void jevFailureKeepsHeuristicVerdict() {
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.noul(anyString(), anyString())).thenReturn(Optional.empty());
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isFalse();
    }

    @Test
    void disabledFlagNeverConsultsJev() {
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, false, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isFalse();
        verifyNoInteractions(decisions);
    }

    @Test
    void missingJevBeanKeepsHeuristicOnly() {
        QueryComplexityClassifier c = new QueryComplexityClassifier((DecisionEngine) null, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isFalse();
        assertThat(c.isSimple(SIMPLE_MSG)).isTrue();
    }

    // --- Tenant policy + per-agent threshold (V334 control surface) ---------

    @Test
    void tenantPolicyOffSkipsJevEvenWhenPlatformOn() {
        // capability_policy costRouting=false - the tenant's explicit opt-out wins
        // over the enabled platform flag; the model is never even asked.
        when(decisions.tenantPolicy()).thenReturn(Optional.of(new DecisionPolicyView(false, null, null)));
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isFalse();
        verify(decisions, never()).noul(anyString(), anyString());
    }

    @Test
    void platformFlagOffIsAHardGateTenantCannotOverride() {
        // Inverse direction: platform off + tenant explicitly on = still off.
        // A deployment-level disable means the feature itself is shut down,
        // not merely defaulted-off.
        when(decisions.tenantPolicy()).thenReturn(Optional.of(new DecisionPolicyView(true, null, null)));
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, false, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isFalse();
        verifyNoInteractions(decisions);
    }

    @Test
    void agentThresholdOverrideWinsOverPlatformDefault() {
        // The model says 0.8 - clears the 0.75 platform default but not this
        // agent's stricter 0.9 override.
        when(decisions.tenantPolicy()).thenReturn(Optional.empty());
        when(decisions.noul(anyString(), anyString())).thenReturn(Optional.of(0.8));
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG, 0.9)).isFalse();
        assertThat(c.isSimple(COMPLEX_LOOKING_MSG, 0.7)).isTrue();
    }

    @Test
    void tenantThresholdOverrideWinsOverPlatformDefault() {
        when(decisions.tenantPolicy()).thenReturn(Optional.of(new DecisionPolicyView(null, null, 0.95)));
        when(decisions.noul(anyString(), anyString())).thenReturn(Optional.of(0.9));
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG)).isFalse();
    }

    @Test
    void agentThresholdBeatsTenantThreshold() {
        // agent 0.6 overrides tenant 0.95 - the model's 0.8 clears the agent bar.
        when(decisions.tenantPolicy()).thenReturn(Optional.of(new DecisionPolicyView(null, null, 0.95)));
        when(decisions.noul(anyString(), anyString())).thenReturn(Optional.of(0.8));
        QueryComplexityClassifier c = new QueryComplexityClassifier(decisions, true, 0.75);

        assertThat(c.isSimple(COMPLEX_LOOKING_MSG, 0.6)).isTrue();
    }
}
