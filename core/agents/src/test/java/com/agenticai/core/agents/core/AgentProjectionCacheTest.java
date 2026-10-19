package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentProjectionCacheTest {

    private final AgentProjectionLoader loader = mock(AgentProjectionLoader.class);
    private final UUID agentId = UUID.randomUUID();
    private final AgentProjection projection = projectionWith(true);

    @BeforeEach
    void inTenantA() {
        TenantContext.set(TenantContext.of("tenant_a", "user", Set.of()));
        when(loader.load(agentId)).thenReturn(projection);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private AgentProjectionCache cache(Duration ttl) {
        ObjectProvider<MeterRegistry> noMeters = mock(ObjectProvider.class);
        return new AgentProjectionCache(loader, noMeters, ttl);
    }

    @Test
    void repeatedReadsWithinTheTtlLoadOnce() {
        AgentProjectionCache c = cache(Duration.ofSeconds(5));
        for (int i = 0; i < 50; i++) {
            assertThat(c.get(agentId)).isSameAs(projection);
        }
        verify(loader, times(1)).load(agentId);
    }

    @Test
    void zeroTtlReadsTheRowEveryTime() {
        AgentProjectionCache c = cache(Duration.ZERO);
        c.get(agentId);
        c.get(agentId);
        verify(loader, times(2)).load(agentId);
    }

    @Test
    void aNegativeOrHugeTtlIsClamped() {
        assertThat(AgentProjectionCache.clamp(Duration.ofSeconds(-3))).isEqualTo(Duration.ZERO);
        assertThat(AgentProjectionCache.clamp(Duration.ofHours(1)))
                .isEqualTo(AgentProjectionCache.MAX_TTL);
        assertThat(AgentProjectionCache.clamp(null)).isEqualTo(Duration.ZERO);
    }

    @Test
    void oneTenantsEntryIsNeverServedToAnother() {
        AgentProjectionCache c = cache(Duration.ofSeconds(5));
        c.get(agentId);
        TenantContext.set(TenantContext.of("tenant_b", "user", Set.of()));
        AgentProjection other = projectionWith(false);
        when(loader.load(agentId)).thenReturn(other);
        assertThat(c.get(agentId))
                .as("same agent id, different tenant: must load for tenant_b")
                .isSameAs(other);
        verify(loader, times(2)).load(agentId);
    }

    @Test
    void withoutATenantContextNothingIsCached() {
        AgentProjectionCache c = cache(Duration.ofSeconds(5));
        TenantContext.clear();
        c.get(agentId);
        c.get(agentId);
        verify(loader, times(2)).load(agentId);
    }

    @Test
    void anAgentWriteOnThisPodEvictsOnlyThatAgent() {
        AgentProjectionCache c = cache(Duration.ofSeconds(5));
        UUID otherAgent = UUID.randomUUID();
        when(loader.load(otherAgent)).thenReturn(projectionWith(true));
        c.get(agentId);
        c.get(otherAgent);

        HotpathCaches.evictAgent("tenant_a", agentId.toString());

        c.get(agentId);
        c.get(otherAgent);
        verify(loader, times(2)).load(agentId);
        verify(loader, times(1)).load(otherAgent);
    }

    @Test
    void evictingWithoutAnIdDropsEverything() {
        AgentProjectionCache c = cache(Duration.ofSeconds(5));
        c.get(agentId);
        HotpathCaches.evictAgent(null, null);
        c.get(agentId);
        verify(loader, times(2)).load(agentId);
    }

    @Test
    void aMissingAgentIsNeverCached() {
        AgentProjectionCache c = cache(Duration.ofSeconds(5));
        UUID gone = UUID.randomUUID();
        when(loader.load(gone)).thenThrow(new AgentNotFoundException(gone));
        assertThatThrownBy(() -> c.get(gone)).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> c.get(gone)).isInstanceOf(AgentNotFoundException.class);
        verify(loader, times(2)).load(gone);
    }

    @Test
    void aTreatmentCloneTakesItsControlAgentsRunBudget() {
        Agent control = agent();
        control.setRunBudgetUsd(new java.math.BigDecimal("2.50"));
        UUID controlId = UUID.randomUUID();
        Agent clone =
                new Agent(
                        "clone",
                        "p",
                        ProviderName.OPENAI,
                        "gpt-4o-mini",
                        100,
                        0.2,
                        1.0,
                        true,
                        ExperienceMode.SIMPLE,
                        ReasoningDepth.FAST,
                        TuningMode.values()[0],
                        controlId);

        AgentProjection p =
                AgentProjection.from(
                        clone,
                        id -> id.equals(controlId) ? Optional.of(control) : Optional.empty());

        assertThat(p.runBudgetUsd()).isEqualByComparingTo("2.50");
    }

    @Test
    void theSetsInASnapshotAreUnmodifiableCopies() {
        Agent a = agent();
        a.setAllowedToolNames(Set.of("web_search"));
        AgentProjection p = AgentProjection.from(a, id -> Optional.empty());
        a.setAllowedToolNames(Set.of("send_email"));
        assertThat(p.allowedToolNames()).containsExactly("web_search");
        assertThatThrownBy(() -> p.allowedToolNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Agent agent() {
        return new Agent(
                "a",
                "p",
                ProviderName.OPENAI,
                "gpt-4o-mini",
                100,
                0.2,
                1.0,
                true,
                ExperienceMode.SIMPLE);
    }

    private static AgentProjection projectionWith(boolean knowledgeBase) {
        Agent a = agent();
        a.setKnowledgeBaseEnabled(knowledgeBase);
        return AgentProjection.from(a, id -> Optional.empty());
    }
}
