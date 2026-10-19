package com.agenticai.core.agents.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.capability.CapabilityPolicyService;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DecisionEngine} routing coverage - the capability facade the
 * consumers and both REST surfaces share. The contract: a model earns calls
 * only when enabled AND key-resolved, a TENANT-sourced key wins over
 * platform/env, and every assess-style method delegates to exactly that
 * model (the model's own fail-open contract covers failure - the engine
 * never retries a second provider behind the caller's back, since two
 * providers answering one decision doubles the spend a fallback would save).
 */
class DecisionEngineTest {

    private final DecisionModel jev = mock(DecisionModel.class);
    private final DecisionModel other = mock(DecisionModel.class);
    private final CapabilityPolicyService policies = mock(CapabilityPolicyService.class);
    private final DecisionEngine engine =
        new DecisionEngine(List.of(jev, other), policies, Duration.ZERO);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void unavailableWhenNoModelCanServe() {
        when(jev.isEnabled()).thenReturn(true);
        when(other.isEnabled()).thenReturn(true);
        when(jev.isAvailable()).thenReturn(false);
        when(other.isAvailable()).thenReturn(false);

        assertThat(engine.isAvailable()).isFalse();
        assertThat(engine.active()).isEmpty();
        assertThat(engine.noul("s", "q?")).isEmpty();
    }

    @Test
    void enabledButKeylessModelCannotServe() {
        // isEnabled without isAvailable = flag on but no key - must not earn calls.
        when(jev.isEnabled()).thenReturn(true);
        when(jev.isAvailable()).thenReturn(false);
        when(other.isEnabled()).thenReturn(false);

        assertThat(engine.isEnabled()).isTrue();
        assertThat(engine.active()).isEmpty();
    }

    @Test
    void firstAvailableModelServesWhenNeitherIsTenantKeyed() {
        when(jev.isEnabled()).thenReturn(true);
        when(jev.isAvailable()).thenReturn(true);
        when(jev.keySource()).thenReturn(Optional.of(DecisionModel.KeySource.PLATFORM));
        when(other.isEnabled()).thenReturn(true);
        when(other.isAvailable()).thenReturn(true);
        when(other.keySource()).thenReturn(Optional.of(DecisionModel.KeySource.ENV));
        when(jev.noul(anyString(), anyString())).thenReturn(Optional.of(0.9));

        assertThat(engine.active()).hasValue(jev);
        assertThat(engine.noul("s", "q?")).hasValue(0.9);
        verify(other, never()).noul(anyString(), anyString());
    }

    @Test
    void tenantKeyedModelWinsOverPlatformKeyed() {
        // Tenant explicitly pays for `other` - it beats jev's platform key
        // even though jev is first in the list.
        when(jev.isEnabled()).thenReturn(true);
        when(jev.isAvailable()).thenReturn(true);
        when(jev.keySource()).thenReturn(Optional.of(DecisionModel.KeySource.PLATFORM));
        when(other.isEnabled()).thenReturn(true);
        when(other.isAvailable()).thenReturn(true);
        when(other.keySource()).thenReturn(Optional.of(DecisionModel.KeySource.TENANT));
        when(other.noul(anyString(), anyString())).thenReturn(Optional.of(0.7));

        assertThat(engine.active()).hasValue(other);
        assertThat(engine.noul("s", "q?")).hasValue(0.7);
    }

    @Test
    void disabledTenantKeyedModelLosesToEnabledPlatformKeyed() {
        // A tenant key on a disabled model must not earn the call - the
        // provider's kill-switch is a hard gate, not a preference.
        when(jev.isEnabled()).thenReturn(false);
        when(other.isEnabled()).thenReturn(true);
        when(other.isAvailable()).thenReturn(true);
        when(other.keySource()).thenReturn(Optional.of(DecisionModel.KeySource.PLATFORM));

        assertThat(engine.active()).hasValue(other);
        verify(jev, never()).isAvailable();
    }

    @Test
    void tenantPolicyIsEmptyWithoutTenantContext() {
        // capability_policy is a tenant-schema table - a tenant-less call
        // must never reach for it (the public-schema miss would be a 500).
        assertThat(engine.tenantPolicy()).isEmpty();
        verify(policies, never()).currentOverrides(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tenantPolicyDecodesTheDecisionRow() {
        TenantContext.set(TenantContext.of("tenant-1", "user", Set.of()));
        when(policies.currentOverrides(com.agenticai.core.providers.capability.Capability.DECISION))
            .thenReturn(Optional.of(Map.of("costRouting", false, "simpleThreshold", 0.9)));

        Optional<DecisionPolicyView> view = engine.tenantPolicy();

        assertThat(view).isPresent();
        assertThat(view.get().costRouting()).isFalse();
        assertThat(view.get().simpleThreshold()).isEqualTo(0.9);
        assertThat(view.get().agentRouting()).isNull();
    }

    @Test
    void chooseAndNoulsDelegateToTheActiveModel() {
        when(jev.isEnabled()).thenReturn(true);
        when(jev.isAvailable()).thenReturn(true);
        when(jev.keySource()).thenReturn(Optional.of(DecisionModel.KeySource.ENV));
        when(other.isEnabled()).thenReturn(false);
        when(jev.choose(anyString(), anyString(), anyMap()))
            .thenReturn(Optional.of(new DecisionModel.Choice("a", Map.of(), 0.8)));
        when(jev.nouls(anyString(), anyMap())).thenReturn(Map.of("k", 0.6));

        assertThat(engine.choose("s", "q?", Map.of("a", "A", "b", "B")).get().choice()).isEqualTo("a");
        assertThat(engine.nouls("s", Map.of("k", "q?"))).containsEntry("k", 0.6);
        verify(other, never()).choose(anyString(), anyString(), anyMap());
        verify(other, never()).nouls(anyString(), anyMap());
    }
}
