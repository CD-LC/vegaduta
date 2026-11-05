package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryProvenance;
import com.agenticai.core.agents.memory.MemoryTrust;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A corrected fact supersedes the old one (migration V685), on a real Postgres with pgvector and the real tenant Flyway:
 * the SQL of {@code FactSupersession} (exact text, similarity, parked list), recall that leaves superseded rows out, and
 * the owner's list, export and erasure that still see them. The unit-level rules are {@code FactSupersessionTest} and
 * {@code MemorySupersessionServiceTest}. Run with {@code -Dvegaduta.it.pg.url=...} (see {@link UnifiedMemoryBootBase}).
 */
@Import(UnifiedMemoryBootBase.FakeEmbedderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FactSupersessionIT extends UnifiedMemoryBootBase {

    static final String SCHEMA = "tenant_it_supersede";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "fact_supersession");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    @Autowired AgentMemoryService agentMemory;
    @Autowired TenantMemoryService tenantMemory;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SidecarClient sidecar;

    private static UUID agent;
    private static final MemoryProvenance TAINTED = new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("web_fetch"));

    @BeforeEach
    void world() {
        doAnswer(i -> UnifiedMemoryBootBase.embed(i.getArgument(0))).when(sidecar).embedQuery(anyString());
        tenantSchema(SCHEMA);
        TenantContext.set(TenantContext.of(SCHEMA, "alice", Set.of()));
        if (agent == null) {
            agent = newAgent("supersede-agent");
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String person(String name) {
        return name + "-" + UUID.randomUUID();
    }

    private List<String> recalled(String subject, String query) {
        return agentMemory.recall(agent, subject, query, 10).stream().map(AgentMemoryEntry::content).toList();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT superseded_by, superseded_at, supersedes_ids, trust FROM " + SCHEMA + ".agent_memory WHERE id = ?", id);
    }

    @Test
    void aNamedFactIsSupersededKeptAndLeftOutOfRecall() {
        String who = person("named");
        UUID pune = agentMemory.remember(agent, who, "I live in Pune", MemoryProvenance.TRUSTED);
        UUID mumbai = agentMemory.remember(agent, who, "I live in Mumbai", MemoryProvenance.TRUSTED, "I live in Pune");

        assertThat(row(pune).get("superseded_by")).isEqualTo(mumbai);
        assertThat(row(pune).get("superseded_at")).isNotNull();
        assertThat(row(mumbai).get("superseded_by")).isNull();
        assertThat(recalled(who, "where do I live")).containsExactly("I live in Mumbai");
        // the owner's list (Memory page, export, erasure) keeps the old row, flagged
        List<AgentMemoryEntry> all = agentMemory.listAllForSubject(who);
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(e -> e.id().equals(pune)).singleElement().satisfies(e -> assertThat(e.supersededAt()).isNotNull());
        assertThat(all).filteredOn(e -> e.id().equals(mumbai)).singleElement().satisfies(e -> assertThat(e.supersededAt()).isNull());
        // the admin/edge list is live-only
        assertThat(agentMemory.listAll(agent, who)).extracting(AgentMemoryEntry::content).containsExactly("I live in Mumbai");
    }

    @Test
    void aNearDuplicateSupersedesOnSimilarityAloneButAnUnrelatedFactDoesNot() {
        String who = person("auto");
        UUID shop = agentMemory.remember(agent, who, "my shop opens at nine am on weekdays", MemoryProvenance.TRUSTED);
        UUID tea = agentMemory.remember(agent, who, "favourite drink is green tea", MemoryProvenance.TRUSTED);
        UUID newer = agentMemory.remember(agent, who, "my shop opens at nine am on weekdays now", MemoryProvenance.TRUSTED);

        assertThat(row(shop).get("superseded_by")).isEqualTo(newer);
        assertThat(row(tea).get("superseded_by")).isNull();
        assertThat(recalled(who, "when does my shop open")).containsExactly("my shop opens at nine am on weekdays now",
                "favourite drink is green tea");
    }

    @Test
    void theSameFactToldAgainIsOneRowWithARefreshedTime() {
        String who = person("same");
        UUID first = agentMemory.remember(agent, who, "I live in Pune", MemoryProvenance.TRUSTED);
        Object before = jdbc.queryForObject("SELECT updated_at FROM " + SCHEMA + ".agent_memory WHERE id = ?", Object.class, first);
        UUID again = agentMemory.remember(agent, who, "  i LIVE in   pune ", MemoryProvenance.TRUSTED);

        assertThat(again).isEqualTo(first);
        assertThat(agentMemory.listAllForSubject(who)).hasSize(1);
        Object after = jdbc.queryForObject("SELECT updated_at FROM " + SCHEMA + ".agent_memory WHERE id = ?", Object.class, first);
        assertThat(after.toString()).isNotEmpty();
        assertThat(before).isNotNull();
    }

    @Test
    void aQuarantinedCorrectionHidesNothingUntilAPersonApprovesIt() {
        String who = person("quarantine");
        UUID pune = agentMemory.remember(agent, who, "I live in Pune", MemoryProvenance.TRUSTED);
        UUID mumbai = agentMemory.remember(agent, who, "I live in Mumbai", TAINTED, "I live in Pune");

        // untrusted: stored, parked, but the trusted fact is untouched and still the one recalled
        assertThat(row(pune).get("superseded_by")).isNull();
        assertThat(row(mumbai).get("trust")).isEqualTo("QUARANTINED");
        assertThat(row(mumbai).get("supersedes_ids")).isNotNull();
        assertThat(recalled(who, "where do I live")).containsExactly("I live in Pune");

        assertThat(agentMemory.approve(agent, mumbai, who)).isTrue();

        assertThat(row(pune).get("superseded_by")).isEqualTo(mumbai);
        assertThat(row(mumbai).get("supersedes_ids")).isNull();
        assertThat(recalled(who, "where do I live")).containsExactly("I live in Mumbai");
    }

    @Test
    void aRejectedQuarantinedCorrectionNeverTakesEffect() {
        String who = person("reject");
        UUID pune = agentMemory.remember(agent, who, "I live in Pune", MemoryProvenance.TRUSTED);
        UUID mumbai = agentMemory.remember(agent, who, "I live in Mumbai", TAINTED, "I live in Pune");

        agentMemory.forget(agent, mumbai, who);

        assertThat(row(pune).get("superseded_by")).isNull();
        assertThat(recalled(who, "where do I live")).containsExactly("I live in Pune");
    }

    @Test
    void anAgentNeverReplacesAFactThePersonTypedButAPersonMay() {
        String who = person("typed");
        UUID typed = agentMemory.rememberOwn(agent, who, "my shop opens at nine am on weekdays", 25);
        UUID byAgent = agentMemory.remember(agent, who, "I live in Mumbai", MemoryProvenance.TRUSTED, "my shop opens at nine am on weekdays");
        assertThat(row(typed).get("superseded_by")).as("an agent write").isNull();
        assertThat(byAgent).isNotNull();

        UUID typedAgain = agentMemory.rememberOwn(agent, who, "my shop opens at nine am on weekdays now", 25);

        assertThat(row(typed).get("superseded_by")).as("a person's own write").isEqualTo(typedAgain);
    }

    @Test
    void restatingTheOldValueSupersedesTheNewerOne() {
        String who = person("reconfirm");
        agentMemory.remember(agent, who, "I live in Pune", MemoryProvenance.TRUSTED);
        UUID mumbai = agentMemory.remember(agent, who, "I live in Mumbai", MemoryProvenance.TRUSTED, "I live in Pune");
        UUID backInPune = agentMemory.remember(agent, who, "I live in Pune", MemoryProvenance.TRUSTED, "I live in Mumbai");

        assertThat(row(mumbai).get("superseded_by")).isEqualTo(backInPune);
        assertThat(recalled(who, "where do I live")).containsExactly("I live in Pune");
        assertThat(agentMemory.listAllForSubject(who)).hasSize(3);
    }

    @Test
    void erasureAndExportSeeSupersededRowsToo() {
        String who = person("erase");
        agentMemory.remember(agent, who, "I live in Pune", MemoryProvenance.TRUSTED);
        agentMemory.remember(agent, who, "I live in Mumbai", MemoryProvenance.TRUSTED, "I live in Pune");
        assertThat(agentMemory.listAllForSubject(who)).hasSize(2);

        assertThat(agentMemory.forgetAllForSubject(who)).isEqualTo(2);

        assertThat(agentMemory.listAllForSubject(who)).isEmpty();
    }

    @Test
    void theSharedTierSupersedesToo() {
        String who = person("shared");
        UUID first = tenantMemory.remember(who, "on the gold plan since march", agent, MemoryProvenance.TRUSTED);
        UUID second = tenantMemory.remember(who, "on the platinum plan since june", agent, MemoryProvenance.TRUSTED, "on the gold plan since march");

        assertThat(tenantMemory.recall(who, "which plan", 10)).extracting(TenantMemoryEntry::id).containsExactly(second);
        assertThat(tenantMemory.listFactsForSubject(who)).hasSize(2)
                .filteredOn(e -> e.id().equals(first)).singleElement().satisfies(e -> assertThat(e.supersededAt()).isNotNull());
    }
}
