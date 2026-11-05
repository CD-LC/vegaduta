package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SemanticMemorySourceTest {

    private final AgentMemoryService agent = mock(AgentMemoryService.class);
    private final TenantMemoryService tenant = mock(TenantMemoryService.class);
    private final UUID agentId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-01T00:00:00Z");

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private SemanticMemorySource source() {
        return new SemanticMemorySource(provider(agent), provider(tenant));
    }

    private MemoryRecallRequest request(Set<MemoryKind> kinds) {
        return new MemoryRecallRequest(agentId, "user-1", "invoices", kinds, 5, 800);
    }

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TenantContext.of("t1", "user-1", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AgentMemoryEntry agentRow(String text, double distance, MemoryTrust trust) {
        return new AgentMemoryEntry(
                UUID.randomUUID(),
                agentId,
                "user-1",
                text,
                distance,
                now,
                now,
                0,
                trust,
                List.of());
    }

    @Test
    void exposesTrustedAgentAndTenantRowsAsSemanticItems() {
        when(agent.recall(eq(agentId), eq("user-1"), eq("invoices"), anyInt()))
                .thenReturn(List.of(agentRow("prefers INR", 0.2, MemoryTrust.TRUSTED)));
        when(tenant.recall(eq("user-1"), eq("invoices"), anyInt()))
                .thenReturn(
                        List.of(
                                new TenantMemoryEntry(
                                        UUID.randomUUID(),
                                        "user-1",
                                        "net 30",
                                        0.4,
                                        null,
                                        now,
                                        now,
                                        0)));
        MemoryRecallResult r = source().recall(request(Set.of()));
        assertThat(r.items())
                .extracting(MemoryItemView::text)
                .containsExactly("prefers INR", "net 30");
        assertThat(r.items()).allSatisfy(i -> assertThat(i.kind()).isEqualTo(MemoryKind.SEMANTIC));
        assertThat(r.items().get(0).score())
                .isEqualTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aQuarantinedRowThatSlipsThroughIsDropped() {
        when(agent.recall(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(agentRow("poisoned", 0.1, MemoryTrust.QUARANTINED)));
        assertThat(source().recall(request(Set.of())).items()).isEmpty();
    }

    @Test
    void otherKindsAreNotThisSourcesBusiness() {
        assertThat(source().recall(request(Set.of(MemoryKind.EPISODIC))).items()).isEmpty();
        verify(agent, never()).recall(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void noTenantBoundFailsClosed() {
        TenantContext.clear();
        assertThat(source().recall(request(Set.of())).items()).isEmpty();
        verify(agent, never()).recall(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void blankSubjectFailsClosed() {
        MemoryRecallRequest r = new MemoryRecallRequest(agentId, " ", "invoices", Set.of(), 5, 800);
        assertThat(source().recall(r).items()).isEmpty();
        verify(agent, never()).recall(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void storeErrorsDegradeToEmpty() {
        when(agent.recall(any(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("db"));
        MemoryRecallResult r = source().recall(request(Set.of()));
        assertThat(r.items()).isEmpty();
        assertThat(r.degradedReason()).isEqualTo("error");
    }

    @Test
    void noServicesMeansUnavailable() {
        SemanticMemorySource s = new SemanticMemorySource(provider(null), provider(null));
        assertThat(s.recall(request(Set.of())).degradedReason()).isEqualTo("unavailable");
    }
}
