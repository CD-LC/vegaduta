package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.SidecarClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Supersession through the two fact services (V685): trust rules, the re-confirmation of the same text, the statements
 * recall, listing, export and erasure use, the approval hook, and that with the feature off not one statement and not one
 * byte of the tool schema changes. JdbcTemplate is mocked, as in {@code MemoryQuarantineTest}; the SQL itself runs in
 * {@code FactSupersessionIT}.
 */
class MemorySupersessionServiceTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID agentId = UUID.randomUUID();
    private AgentMemoryService agentMemory;
    private TenantMemoryService tenantMemory;

    @BeforeEach
    void setUp() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        agentMemory = new AgentMemoryService(sidecar, jdbc);
        tenantMemory = new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);
    }

    private void supersessionOn() {
        MemorySupersessionProperties on = new MemorySupersessionProperties();
        agentMemory.setSupersession(on);
        tenantMemory.setSupersession(on);
    }

    private List<String> statements(String method) {
        List<String> out = new ArrayList<>();
        for (Invocation i : mockingDetails(jdbc).getInvocations()) {
            if (i.getMethod().getName().equals(method) && i.getArguments().length > 0) {
                out.add(String.valueOf(i.getArguments()[0]));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void automaticRuleFinds(UUID old) {
        when(jdbc.queryForList(contains("<="), eq(UUID.class), any(Object[].class))).thenReturn(List.of(old));
    }

    // ------------------------------------------------------------------ writes

    @Test
    void aTrustedFactSupersedesTheOlderFactItIsNearTo() {
        supersessionOn();
        UUID old = UUID.randomUUID();
        automaticRuleFinds(old);

        UUID id = agentMemory.remember(agentId, "alice", "I live in Mumbai", MemoryProvenance.TRUSTED);

        assertThat(statements("update")).anyMatch(s -> s.contains("INSERT INTO"))
                .anyMatch(s -> s.contains("SET superseded_by = ?, superseded_at = ?"));
        assertThat(id).isNotNull();
        // the old row is marked, never deleted
        assertThat(statements("update")).noneMatch(s -> s.contains("DELETE FROM") && !s.contains("ORDER BY"));
    }

    @Test
    void aQuarantinedFactReplacesNothingUntilAPersonApprovesIt() {
        supersessionOn();
        automaticRuleFinds(UUID.randomUUID());
        MemoryProvenance tainted = new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("web_fetch"));

        agentMemory.remember(agentId, "alice", "I live in Mumbai", tainted);

        List<String> updates = statements("update");
        assertThat(updates).anyMatch(s -> s.contains("SET supersedes_ids = ?::uuid[]"));
        assertThat(updates).noneMatch(s -> s.contains("SET superseded_by"));
    }

    @Test
    void aQuarantinedFactAlsoNeverReconfirmsATrustedOne() {
        supersessionOn();
        MemoryProvenance tainted = new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("web_fetch"));

        agentMemory.remember(agentId, "alice", "I live in Pune", tainted);

        assertThat(statements("queryForList")).noneMatch(s -> s.contains("lower(regexp_replace"));
        assertThat(statements("update")).anyMatch(s -> s.contains("INSERT INTO"));
    }

    @Test
    void approvingAParkedFactAppliesTheSupersession() {
        supersessionOn();
        UUID memoryId = UUID.randomUUID();
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), any(Object[].class))).thenReturn(1);

        assertThat(agentMemory.approve(agentId, memoryId)).isTrue();

        assertThat(statements("update")).anyMatch(s -> s.contains("unnest(supersedes_ids)"))
                .anyMatch(s -> s.contains("SET supersedes_ids = NULL"));
    }

    @Test
    void approvingASharedFactAppliesItToo() {
        supersessionOn();
        UUID memoryId = UUID.randomUUID();
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), any(Object[].class))).thenReturn(1);

        assertThat(tenantMemory.approveFact(memoryId, "alice")).isTrue();

        assertThat(statements("update")).anyMatch(s -> s.contains("unnest(supersedes_ids)"));
    }

    @Test
    void anApprovalThatDidNothingAppliesNothing() {
        supersessionOn();
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), any(Object[].class))).thenReturn(0);

        assertThat(agentMemory.approve(agentId, UUID.randomUUID())).isFalse();

        assertThat(statements("update")).noneMatch(s -> s.contains("unnest(supersedes_ids)"));
    }

    @Test
    void theSameFactToldAgainIsReconfirmedWithoutANewRowOrAnEmbedding() {
        supersessionOn();
        UUID existing = UUID.randomUUID();
        when(jdbc.queryForList(contains("lower(regexp_replace"), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(existing));

        UUID id = agentMemory.remember(agentId, "alice", "I live in Pune", MemoryProvenance.TRUSTED);

        assertThat(id).isEqualTo(existing);
        assertThat(statements("update")).noneMatch(s -> s.contains("INSERT INTO"))
                .anyMatch(s -> s.contains("SET updated_at = ?"));
        verify(sidecar, never()).embedQuery(anyString());
    }

    @Test
    void theSameSharedFactToldAgainIsReconfirmedToo() {
        supersessionOn();
        UUID existing = UUID.randomUUID();
        when(jdbc.queryForList(contains("lower(regexp_replace"), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(existing));

        assertThat(tenantMemory.remember("alice", "Gold plan", agentId, MemoryProvenance.TRUSTED)).isEqualTo(existing);
        assertThat(statements("update")).noneMatch(s -> s.contains("INSERT INTO"));
    }

    @Test
    void theReplacesArgumentReachesTheWriter() {
        AgentMemoryService service = mock(AgentMemoryService.class);
        when(service.remember(any(), anyString(), anyString(), any(), anyString())).thenReturn(UUID.randomUUID());
        RememberFactToolCallback tool = new RememberFactToolCallback(service, agentId, "alice", null, null, null, null, null);

        tool.call("{\"fact\":\"I live in Mumbai\",\"replaces\":\"I live in Pune\"}");

        // an unreadable taint state quarantines the write, so it is the 5-argument overload that must be used
        verify(service).remember(eq(agentId), eq("alice"), eq("I live in Mumbai"), any(), eq("I live in Pune"));
    }

    @Test
    void aReplacesArgumentThatIsNotAShortFactIsIgnored() {
        AgentMemoryService service = mock(AgentMemoryService.class);
        when(service.remember(any(), anyString(), anyString(), any())).thenReturn(UUID.randomUUID());
        RememberFactToolCallback tool = new RememberFactToolCallback(service, agentId, "alice", null, null, null, null, null);

        tool.call("{\"fact\":\"x\",\"replaces\":\"" + "y".repeat(RememberFactToolCallback.MAX_REPLACES_CHARS + 1) + "\"}");

        verify(service).remember(eq(agentId), eq("alice"), eq("x"), any());
        verify(service, never()).remember(any(), anyString(), anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void recallLeavesSupersededRowsOutButTheOwnersListsKeepThem() {
        agentMemory.recall(agentId, "alice", "where do I live", 3);
        agentMemory.listAll(agentId, "alice");
        agentMemory.listAllForSubject("alice");
        tenantMemory.recall("alice", "where do I live", 3);

        List<String> queries = statements("query");
        assertThat(queries).hasSize(4);
        assertThat(queries.get(0)).contains("superseded_by IS NULL").contains("ORDER BY distance ASC, updated_at DESC");
        assertThat(queries.get(1)).contains("superseded_by IS NULL"); // the edge endpoint must not send stale facts
        assertThat(queries.get(2)).doesNotContain("superseded_by IS NULL").contains("superseded_at"); // page, export, erasure
        assertThat(queries.get(3)).contains("superseded_by IS NULL");
    }

    @Test
    void erasureDeletesEveryRowOfThePersonSupersededOnesIncluded() {
        agentMemory.forgetAllForSubject("alice");
        tenantMemory.forgetAllForSubject("alice");

        assertThat(statements("update")).hasSize(2).allMatch(s -> s.startsWith("DELETE FROM") && !s.contains("superseded"));
    }

    @Test
    void whenTheCapPrunesItDropsSupersededRowsFirst() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1000);

        agentMemory.remember(agentId, "alice", "a fact", MemoryProvenance.TRUSTED);

        assertThat(statements("update")).anyMatch(s -> s.contains("ORDER BY (superseded_by IS NOT NULL) DESC"));
    }

    // ------------------------------------------------------------------ off

    @Test
    void withTheFeatureOffNotOneExtraStatementIsIssuedOnAWrite() {
        // a hand-built service, and one given the property bean with enabled=false
        agentMemory.remember(agentId, "alice", "I live in Pune", MemoryProvenance.TRUSTED);
        MemorySupersessionProperties disabled = new MemorySupersessionProperties();
        disabled.setEnabled(false);
        agentMemory.setSupersession(disabled);
        agentMemory.remember(agentId, "alice", "I live in Pune", MemoryProvenance.TRUSTED);

        assertThat(statements("queryForList")).isEmpty();
        assertThat(statements("update")).filteredOn(s -> !s.contains("INSERT INTO")).isEmpty();
        assertThat(agentMemory.supersessionEnabled()).isFalse();
    }

    @Test
    void withTheFeatureOffTheToolSchemaIsTheShippedOne() {
        String shipped =
                """
                {
                    "type": "object",
                    "properties": {
                        "fact": {
                            "type": "string",
                            "description": "The fact to remember, written as a short, self-contained statement (e.g. \\"Prefers to be contacted in the evening after 6pm\\")"
                        }
                    },
                    "required": ["fact"]
                }
                """;

        assertThat(new RememberFactToolCallback(agentMemory, agentId, "alice").getToolDefinition().inputSchema())
                .isEqualTo(shipped);
        assertThat(new RememberFactToolCallback(null, agentId, "alice").getToolDefinition().inputSchema()).isEqualTo(shipped);
    }

    @Test
    void withTheFeatureOnTheToolOffersReplaces() {
        supersessionOn();

        assertThat(new RememberFactToolCallback(agentMemory, agentId, "alice").getToolDefinition().inputSchema())
                .contains("\"replaces\"").contains("\"required\": [\"fact\"]");
    }
}
