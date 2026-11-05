package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** The tombstone guard: no SQL and no lookups when inactive (the flags-off budget), an indexed predicate when active. */
class MemoryTombstoneGuardTest {

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant_a", "tester", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcTemplate> provider(JdbcTemplate jdbc) {
        ObjectProvider<JdbcTemplate> p = mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(jdbc);
        return p;
    }

    @Test
    void inactiveAddsNothingToAnyStatementAndNeverTouchesTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MemoryTombstoneGuard g = new MemoryTombstoneGuard(false, provider(jdbc));
        assertThat(g.active()).isFalse();
        assertThat(g.excludeAgentFacts("t")).isEmpty();
        assertThat(g.excludeSharedFacts("t")).isEmpty();
        assertThat(g.excludeItems("t")).isEmpty();
        assertThat(g.isForgotten(MemoryTombstoneGuard.KIND_AGENT, "s", UUID.randomUUID(), "text")).isFalse();
        assertThat(g.reconfirm(MemoryTombstoneGuard.KIND_AGENT, "s", UUID.randomUUID(), "text")).isZero();
        verifyNoInteractions(jdbc);
        assertThat(MemoryTombstoneGuard.inactive().excludeItems("t")).isEmpty();
    }

    @Test
    void activeExcludesByTheIndexedSubjectAndHashPairInsideTheRecallStatement() {
        MemoryTombstoneGuard g = new MemoryTombstoneGuard(true, provider(mock(JdbcTemplate.class)));
        String agent = g.excludeAgentFacts("\"tenant_a\".agent_memory");
        assertThat(agent).startsWith(" AND NOT EXISTS (SELECT 1 FROM \"tenant_a\".memory_tombstone t")
                .contains("t.memory_kind = 'agent'")
                .contains("t.subject_key = \"tenant_a\".agent_memory.subject_key")
                .contains("t.agent_id = \"tenant_a\".agent_memory.agent_id")
                .contains("t.content_sha256 = encode(sha256(convert_to(\"tenant_a\".agent_memory.content, 'UTF8')), 'hex')");
        assertThat(g.excludeSharedFacts("\"tenant_a\".tenant_memory"))
                .contains("t.memory_kind = 'shared'")
                .doesNotContain("agent_id");
        assertThat(g.excludeItems("\"tenant_a\".memory_item"))
                .contains("t.memory_kind = 'item'")
                .contains("t.subject_key = 'item:' || \"tenant_a\".memory_item.agent_id::text");
    }

    @Test
    void theHashIsLowercaseHexSha256OfTheExactText() {
        assertThat(MemoryTombstoneGuard.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(MemoryTombstoneGuard.sha256("abc ")).isNotEqualTo(MemoryTombstoneGuard.sha256("abc"));
        assertThat(MemoryTombstoneGuard.sha256("é")).hasSize(64);
    }

    @Test
    void aLookupErrorFailsOpenForTheWritePathButNeverThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Boolean>>any(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));
        MemoryTombstoneGuard g = new MemoryTombstoneGuard(true, provider(jdbc));
        assertThat(g.isForgotten(MemoryTombstoneGuard.KIND_SHARED, "s", null, "text")).isFalse();
    }

    @Test
    void theProposalSinkRefusesATextTheConsolidationForgotForThatAgent() {
        StoreFixture f = new StoreFixture();
        try {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            org.mockito.Mockito.when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class), any(Object[].class)))
                    .thenReturn(true);
            var embedders = new org.springframework.beans.factory.support.StaticListableBeanFactory();
            embedders.addBean("e", (MemoryEmbedder) StoreFixture::vector);
            var noMeters = new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class);
            StoreMemoryProposalSink sink = new StoreMemoryProposalSink(
                    f.props, f.gate, f.storage, embedders.getBeanProvider(MemoryEmbedder.class),
                    java.util.List.of(new BasicMemoryContentScreen(f.props)), noMeters, f.clock,
                    new MemoryTombstoneGuard(true, provider(jdbc)));

            StoreMemoryProposalSink.Outcome o = sink.proposeDetailed(
                    f.proposal(com.agenticai.core.agents.memory.spi.MemoryKind.PROCEDURAL, "forgotten procedure"));

            assertThat(o.accepted()).isFalse();
            assertThat(o.refusal()).isEqualTo("FORGOTTEN");
            assertThat(f.storage.items).isEmpty();
        } finally {
            f.close();
        }
    }

    @Test
    void itemTombstonesLiveUnderAnAgentScopedSubject() {
        UUID agent = UUID.randomUUID();
        assertThat(MemoryTombstoneGuard.itemSubject(agent)).isEqualTo("item:" + agent);
    }
}
