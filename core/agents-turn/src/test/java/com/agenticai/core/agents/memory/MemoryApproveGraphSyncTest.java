package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.SidecarClient;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * G6: approving a quarantined fact offers it to the graph AFTER the approval committed; the graph
 * part is fail-open, so the approval's result never depends on it. JdbcTemplate is mocked, as in
 * {@link MemoryQuarantineTest}.
 */
class MemoryApproveGraphSyncTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApprovedFactGraphSync sync = mock(ApprovedFactGraphSync.class);
    private final UUID agentId = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private AgentMemoryService agentMemory;
    private TenantMemoryService tenantMemory;

    @BeforeEach
    void setUp() {
        agentMemory = new AgentMemoryService(sidecar, jdbc);
        agentMemory.setGraphSync(sync);
        tenantMemory = new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);
        tenantMemory.setGraphSync(sync);
    }

    /** The row the post-approval SELECT reads. */
    private void rowIs(String sqlPart, String subject, String content, UUID createdBy)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("subject_key")).thenReturn(subject);
        when(rs.getString("content")).thenReturn(content);
        when(rs.getObject("created_by_agent_id", UUID.class)).thenReturn(createdBy);
        doAnswer(
                        inv -> {
                            inv.<RowCallbackHandler>getArgument(1).processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(contains(sqlPart), any(RowCallbackHandler.class), eq(id));
    }

    @Test
    void approvingAnAgentFactOffersItToTheGraphWithItsId() throws Exception {
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq(agentId))).thenReturn(1);
        rowIs("SELECT subject_key, content", "alice", "Priya's manager is Raj", null);

        assertThat(agentMemory.approve(agentId, id)).isTrue();

        verify(sync).agentFactApproved(agentId, "alice", id, "Priya's manager is Raj");
    }

    @Test
    void theSubjectScopedApproveAlsoSyncs() throws Exception {
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq(agentId), eq("alice")))
                .thenReturn(1);
        rowIs("SELECT subject_key, content", "alice", "fact", null);

        assertThat(agentMemory.approve(agentId, id, "alice")).isTrue();

        verify(sync).agentFactApproved(agentId, "alice", id, "fact");
    }

    @Test
    void anApprovalThatChangedNothingOffersNothing() {
        when(jdbc.update(anyString(), eq(id), eq(agentId))).thenReturn(0);
        assertThat(agentMemory.approve(agentId, id)).isFalse();
        verify(sync, never()).agentFactApproved(any(), any(), any(), any());
    }

    @Test
    void aFailingGraphLookupNeverFailsTheApproval() {
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq(agentId))).thenReturn(1);
        doThrow(new IllegalStateException("db hiccup"))
                .when(jdbc)
                .query(
                        contains("SELECT subject_key, content"),
                        any(RowCallbackHandler.class),
                        eq(id));

        assertThat(agentMemory.approve(agentId, id)).isTrue();
        verify(sync, never()).agentFactApproved(any(), any(), any(), any());
    }

    @Test
    void aSwitchedOffAgentWriteSwitchSkipsTheGraph() {
        MemoryLimitsProperties off = new MemoryLimitsProperties();
        off.setWriteEnabled(false);
        agentMemory.setLimits(off);
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq(agentId))).thenReturn(1);

        assertThat(agentMemory.approve(agentId, id)).isTrue();
        verify(sync, never()).agentFactApproved(any(), any(), any(), any());
    }

    @Test
    void withoutASyncBeanApprovalIsVectorOnlyAsBefore() {
        AgentMemoryService plain = new AgentMemoryService(sidecar, jdbc);
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq(agentId))).thenReturn(1);
        assertThat(plain.approve(agentId, id)).isTrue();
        verify(jdbc, never())
                .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void approvingASharedFactOffersItToTheSharedTier() throws Exception {
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq("alice"))).thenReturn(1);
        rowIs("SELECT subject_key, content, created_by_agent_id", "alice", "shared fact", creator);

        assertThat(tenantMemory.approveFact(id, "alice")).isTrue();

        verify(sync).sharedFactApproved(creator, "alice", id, "shared fact");
    }

    @Test
    void theSubjectAgnosticSharedApproveSyncsToo() throws Exception {
        when(jdbc.update(contains("SET trust = 'TRUSTED' WHERE id = ?"), eq(id))).thenReturn(1);
        rowIs("SELECT subject_key, content, created_by_agent_id", "bob", "bob's fact", null);

        assertThat(tenantMemory.approveFact(id, null)).isTrue();

        verify(sync).sharedFactApproved(null, "bob", id, "bob's fact");
    }

    @Test
    void aFailingSharedGraphLookupNeverFailsTheApproval() {
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq("alice"))).thenReturn(1);
        doThrow(new IllegalStateException("db hiccup"))
                .when(jdbc)
                .query(
                        contains("SELECT subject_key, content"),
                        any(RowCallbackHandler.class),
                        eq(id));
        assertThat(tenantMemory.approveFact(id, "alice")).isTrue();
    }

    @Test
    void aWrongSubjectApprovesAndSyncsNothing() {
        when(jdbc.update(contains("SET trust = 'TRUSTED'"), eq(id), eq("bob"))).thenReturn(0);
        assertThat(tenantMemory.approveFact(id, "bob")).isFalse();
        verify(sync, never()).sharedFactApproved(any(), any(), any(), any());
    }
}
