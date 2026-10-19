package com.agenticai.core.agents.usermemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryCapExceededException;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@SuppressWarnings("unchecked")
class UserMemoryControllerTest {

    private final AgentMemoryService agent = mock(AgentMemoryService.class);
    private final TenantMemoryService tenant = mock(TenantMemoryService.class);
    private final ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
    private final ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
    private final GraphMemoryService graph = mock(GraphMemoryService.class);
    private final ObjectProvider<GraphMemoryService> graphProvider = mock(ObjectProvider.class);
    private final AgentService agentService = mock(AgentService.class);
    private final Principal alice = () -> "alice";

    private UserMemoryController controller() {
        return controller(true);
    }

    private UserMemoryController controller(boolean selfService) {
        return controller(selfService, false);
    }

    private UserMemoryController controller(boolean selfService, boolean graphForgetSync) {
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        when(graphProvider.getIfAvailable()).thenReturn(graph);
        when(tenant.blocksEnabled()).thenReturn(true);
        return new UserMemoryController(
            agentProvider, tenantProvider, graphProvider, agentService,
            new UserMemoryFactService(agentProvider, tenantProvider, agentService, selfService, 25, 50),
            selfService, graphForgetSync);
    }

    @Test
    void anonymousCallerIsRejected() {
        UserMemoryController c = controller();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> c.overview(null));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }

    @Test
    void overviewReportsUnavailableWithoutMemoryBeans() {
        UserMemoryController c = new UserMemoryController(agentProvider, tenantProvider, graphProvider, agentService,
            new UserMemoryFactService(agentProvider, tenantProvider, agentService, true, 25, 50), true, false);
        UserMemoryController.MemoryOverview o = c.overview(alice);
        assertFalse(o.available());
        assertTrue(o.blocks().isEmpty());
        assertTrue(o.facts().isEmpty());
    }

    @Test
    void overviewListsBlocksAndFactsForTheCallerOnly() {
        UserMemoryController c = controller();
        when(tenant.listBlocks("alice")).thenReturn(List.of(
            new TenantMemoryService.MemoryBlockEntry("persona", "Be terse", Instant.EPOCH)));
        when(agent.listAllForSubject("alice")).thenReturn(List.of());
        when(tenant.listFactsForSubject("alice")).thenReturn(List.of());
        UserMemoryController.MemoryOverview o = c.overview(alice);
        assertTrue(o.available());
        assertEquals(1, o.blocks().size());
        assertEquals("persona", o.blocks().get(0).label());
        verify(agent).listAllForSubject("alice");
    }

    @Test
    void putBlockRejectsUnknownLabelBeforeTouchingStorage() {
        UserMemoryController c = controller();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> c.putBlock("nonsense", new UserMemoryController.ContentRequest("x"), alice));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(tenant, never()).putBlock(anyString(), anyString(), any());
    }

    @Test
    void putBlockMapsServiceValidationTo400() {
        UserMemoryController c = controller();
        doThrow(new IllegalArgumentException("too long")).when(tenant).putBlock("alice", "persona", "x");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> c.putBlock("persona", new UserMemoryController.ContentRequest("x"), alice));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void editFactReturns404WhenNotOwnedAndRejectsBlankText() {
        UserMemoryController c = controller();
        UUID id = UUID.randomUUID();
        when(agent.updateForSubject(id, "alice", "new text")).thenReturn(false);
        ResponseEntity<Void> r = c.editFact("agent", id, new UserMemoryController.ContentRequest("new text"), alice);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> c.editFact("agent", id, new UserMemoryController.ContentRequest("  "), alice));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void deleteFactScopesToCallerSubject() {
        UserMemoryController c = controller();
        UUID id = UUID.randomUUID();
        when(tenant.forgetFactForSubject(id, "alice")).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT, c.deleteFact("shared", id, alice).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> c.deleteFact("bogus", id, alice));
    }

    // ---- self-service: add fact, forget-all, export (app.memory.self-service.enabled) ----

    @Test
    void addAgentFactChecksRunnerAccessThenRemembersUnderTheCaller() {
        UserMemoryController c = controller();
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(agent.rememberOwn(agentId, "alice", "likes tea", 25)).thenReturn(id);
        ResponseEntity<UserMemoryController.FactView> r =
            c.addFact(new UserMemoryController.FactCreateRequest("  likes tea ", "agent", agentId), alice);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals(id, r.getBody().id());
        assertEquals("agent", r.getBody().scope());
        assertEquals(agentId, r.getBody().agentId());
        assertEquals("likes tea", r.getBody().content());
        InOrder order = inOrder(agentService, agent);
        order.verify(agentService).requireAccess(agentId, PermissionLevel.RUNNER);
        order.verify(agent).rememberOwn(agentId, "alice", "likes tea", 25);
        // The agent-initiated path (which prunes across subjects) is never used for a hand-added fact.
        verify(agent, never()).remember(any(), anyString(), anyString());
    }

    @Test
    void addFactOverThePerCallerCapAnswers409ForBothScopes() {
        UserMemoryController c = controller();
        UUID agentId = UUID.randomUUID();
        when(agent.rememberOwn(agentId, "alice", "likes tea", 25)).thenThrow(new MemoryCapExceededException("limit"));
        when(tenant.rememberOwn("alice", "likes tea", 50)).thenThrow(new MemoryCapExceededException("limit"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest("likes tea", "agent", agentId), alice)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest("likes tea", "shared", null), alice)).getStatusCode());
        verify(tenant, never()).remember(anyString(), anyString(), any());
    }

    @Test
    void addAgentFactForAnAgentTheCallerCannotRunWritesNothing() {
        UserMemoryController c = controller();
        UUID agentId = UUID.randomUUID();
        when(agentService.requireAccess(agentId, PermissionLevel.RUNNER)).thenThrow(new ResourceAccessDeniedException());
        assertThrows(ResourceAccessDeniedException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest("likes tea", "agent", agentId), alice));
        verify(agent, never()).rememberOwn(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void addSharedFactHasNoAgentAndNeedsNoAgentAccess() {
        UserMemoryController c = controller();
        UUID id = UUID.randomUUID();
        when(tenant.rememberOwn("alice", "works at Acme", 50)).thenReturn(id);
        ResponseEntity<UserMemoryController.FactView> r =
            c.addFact(new UserMemoryController.FactCreateRequest("works at Acme", "shared", UUID.randomUUID()), alice);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals(id, r.getBody().id());
        assertEquals("shared", r.getBody().scope());
        assertNull(r.getBody().agentId());
        verify(agentService, never()).requireAccess(any(), any());
    }

    @Test
    void addFactValidatesTextScopeAndAgent() {
        UserMemoryController c = controller();
        UUID agentId = UUID.randomUUID();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest(" ", "shared", null), alice)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest("x".repeat(2001), "shared", null), alice)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest("x", "agent", null), alice)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest("x", "bogus", agentId), alice)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> c.addFact(null, alice)).getStatusCode());
        verify(agent, never()).rememberOwn(any(), anyString(), anyString(), anyInt());
        verify(tenant, never()).rememberOwn(anyString(), anyString(), anyInt());
    }

    @Test
    void forgetAllNeedsConfirmAndErasesAllThreeStores() {
        UserMemoryController c = controller();
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> c.forgetAll(false, alice)).getStatusCode());
        verify(agent, never()).forgetAllForSubject(anyString());

        when(agent.forgetAllForSubject("alice")).thenReturn(4);
        when(tenant.forgetAllForSubject("alice")).thenReturn(3);
        when(graph.eraseSubject("alice")).thenReturn(2);
        UserMemoryController.ForgetAllResult r = c.forgetAll(true, alice);
        assertEquals(4, r.agentFacts());
        assertEquals(3, r.sharedAndBlocks());
        assertEquals(2, r.graphEntities());
        assertTrue(r.graphErased());
        assertTrue(r.agentErased());
        assertTrue(r.sharedErased());
    }

    @Test
    void forgetAllStillErasesTheOtherStoresWhenAgentMemoryFailsAndSaysWhich() {
        UserMemoryController c = controller();
        when(agent.forgetAllForSubject("alice")).thenThrow(new RuntimeException("db hiccup"));
        when(tenant.forgetAllForSubject("alice")).thenReturn(3);
        when(graph.eraseSubject("alice")).thenReturn(2);
        UserMemoryController.ForgetAllResult r = c.forgetAll(true, alice);
        assertFalse(r.agentErased());
        assertEquals(0, r.agentFacts());
        assertTrue(r.sharedErased());
        assertEquals(3, r.sharedAndBlocks());
        assertTrue(r.graphErased());
        assertEquals(2, r.graphEntities());
    }

    @Test
    void forgetAllReportsASharedMemoryFailureAndKeepsTheAgentCount() {
        UserMemoryController c = controller();
        when(agent.forgetAllForSubject("alice")).thenReturn(4);
        when(tenant.forgetAllForSubject("alice")).thenThrow(new RuntimeException("db hiccup"));
        UserMemoryController.ForgetAllResult r = c.forgetAll(true, alice);
        assertTrue(r.agentErased());
        assertEquals(4, r.agentFacts());
        assertFalse(r.sharedErased());
        assertEquals(0, r.sharedAndBlocks());
        verify(graph).eraseSubject("alice");
    }

    @Test
    void subjectFingerprintIsStableShortAndNotTheSubject() {
        String fp = UserMemoryController.subjectFingerprint("alice");
        assertEquals(12, fp.length());
        assertEquals(fp, UserMemoryController.subjectFingerprint("alice"));
        assertFalse(fp.contains("alice"));
        assertFalse(fp.equals(UserMemoryController.subjectFingerprint("bob")));
    }

    @Test
    void forgetAllReportsAGraphFailureInsteadOfFailing() {
        UserMemoryController c = controller();
        when(agent.forgetAllForSubject("alice")).thenReturn(1);
        when(tenant.forgetAllForSubject("alice")).thenReturn(1);
        when(graph.eraseSubject("alice")).thenThrow(new RuntimeException("no graph"));
        UserMemoryController.ForgetAllResult r = c.forgetAll(true, alice);
        assertEquals(1, r.agentFacts());
        assertEquals(0, r.graphEntities());
        assertFalse(r.graphErased());
    }

    @Test
    void forgetAllWithoutGraphMemoryStillErasesVectorRows() {
        UserMemoryController c = controller();
        when(graphProvider.getIfAvailable()).thenReturn(null);
        when(agent.forgetAllForSubject("alice")).thenReturn(2);
        UserMemoryController.ForgetAllResult r = c.forgetAll(true, alice);
        assertEquals(2, r.agentFacts());
        assertTrue(r.graphErased());
        verify(tenant).forgetAllForSubject("alice");
    }

    @Test
    void exportIsAJsonAttachmentOfTheCallersOwnData() {
        UserMemoryController c = controller();
        when(tenant.listBlocks("alice")).thenReturn(List.of(
            new TenantMemoryService.MemoryBlockEntry("persona", "Be terse", Instant.EPOCH)));
        when(agent.listAllForSubject("alice")).thenReturn(List.of());
        when(tenant.listFactsForSubject("alice")).thenReturn(List.of());
        ResponseEntity<UserMemoryController.MemoryExport> r = c.export(alice);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        String disposition = r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment;"));
        assertTrue(disposition.contains("memory-export.json"));
        assertEquals(1, r.getBody().blocks().size());
        assertTrue(r.getBody().facts().isEmpty());
        assertTrue(r.getBody().exportedAt().isAfter(Instant.EPOCH));
    }

    @Test
    void selfServiceFlagOffAnswers404OnTheThreeNewEndpointsOnly() {
        UserMemoryController c = controller(false);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> c.addFact(new UserMemoryController.FactCreateRequest("x", "shared", null), alice)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> c.forgetAll(true, alice)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> c.export(alice)).getStatusCode());
        verify(agent, never()).forgetAllForSubject(anyString());
        verify(tenant, never()).forgetAllForSubject(anyString());
        verify(graph, never()).eraseSubject(anyString());
        when(agent.listAllForSubject("alice")).thenReturn(List.of());
        when(tenant.listFactsForSubject("alice")).thenReturn(List.of());
        when(tenant.listBlocks("alice")).thenReturn(List.of());
        UserMemoryController.MemoryOverview o = c.overview(alice);
        assertTrue(o.available());
        assertFalse(o.selfServiceEnabled());
    }

    // ---- graph forget-sync on edit/delete (app.graph-memory.user-forget-sync.enabled, default off) ----

    private AgentMemoryEntry agentFact(UUID id, UUID agentId, String content) {
        return new AgentMemoryEntry(id, agentId, "alice", content, 0.0, Instant.EPOCH, Instant.EPOCH, 0);
    }

    @Test
    void deleteWithGraphSyncInvalidatesEdgesForTheOldTextInTheFactsOwnTier() {
        UserMemoryController c = controller(true, true);
        UUID id = UUID.randomUUID();
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        when(agent.listAllForSubject("alice")).thenReturn(List.of(
            agentFact(id, agentA, "Priya's manager is Raj"),
            agentFact(UUID.randomUUID(), agentA, "Likes tea"),
            agentFact(UUID.randomUUID(), agentB, "Another agent's fact")));
        when(agent.forgetForSubject(id, "alice")).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT, c.deleteFact("agent", id, alice).getStatusCode());
        verify(graph).forgetFactText("alice", agentA, "Priya's manager is Raj", List.of("Likes tea"));
    }

    @Test
    void graphSyncFailureStillAnswers204() {
        UserMemoryController c = controller(true, true);
        UUID id = UUID.randomUUID();
        UUID agentA = UUID.randomUUID();
        when(agent.listAllForSubject("alice")).thenReturn(List.of(agentFact(id, agentA, "Priya's manager is Raj")));
        when(agent.forgetForSubject(id, "alice")).thenReturn(true);
        when(graph.forgetFactText(anyString(), any(), anyString(), any())).thenThrow(new RuntimeException("AGE down"));
        assertEquals(HttpStatus.NO_CONTENT, c.deleteFact("agent", id, alice).getStatusCode());
        verify(graph).forgetFactText(anyString(), any(), anyString(), any());
    }

    @Test
    void graphSyncLookupFailureStillDeletesTheFact() {
        UserMemoryController c = controller(true, true);
        UUID id = UUID.randomUUID();
        when(agent.listAllForSubject("alice")).thenThrow(new RuntimeException("db hiccup"));
        when(agent.forgetForSubject(id, "alice")).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT, c.deleteFact("agent", id, alice).getStatusCode());
        verify(graph, never()).forgetFactText(anyString(), any(), anyString(), any());
    }

    @Test
    void graphSyncIsSkippedWhenTheFactIsNotTheCallers() {
        UserMemoryController c = controller(true, true);
        UUID id = UUID.randomUUID();
        when(agent.listAllForSubject("alice")).thenReturn(List.of());
        when(agent.forgetForSubject(id, "alice")).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, c.deleteFact("agent", id, alice).getStatusCode());
        verify(graph, never()).forgetFactText(anyString(), any(), anyString(), any());
    }

    @Test
    void editWithGraphSyncUsesTheSharedTierAndProtectsEdgesTheNewTextStillSupports() {
        UserMemoryController c = controller(true, true);
        UUID id = UUID.randomUUID();
        when(tenant.listFactsForSubject("alice")).thenReturn(List.of(
            new TenantMemoryEntry(id, "alice", "Works at Acme", 0.0, UUID.randomUUID(), Instant.EPOCH, Instant.EPOCH, 0),
            new TenantMemoryEntry(UUID.randomUUID(), "alice", "Lives in Pune", 0.0, null, Instant.EPOCH, Instant.EPOCH, 0)));
        when(tenant.updateFactForSubject(id, "alice", "Works at Globex")).thenReturn(true);
        ResponseEntity<Void> r = c.editFact("shared", id, new UserMemoryController.ContentRequest("Works at Globex"), alice);
        assertEquals(HttpStatus.NO_CONTENT, r.getStatusCode());
        verify(graph).forgetFactText("alice", null, "Works at Acme", List.of("Lives in Pune", "Works at Globex"));
    }

    @Test
    void graphSyncOffDoesNoExtraReadsAndNeverTouchesTheGraph() {
        UserMemoryController c = controller();
        UUID id = UUID.randomUUID();
        when(agent.forgetForSubject(id, "alice")).thenReturn(true);
        when(agent.updateForSubject(id, "alice", "new text")).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT, c.deleteFact("agent", id, alice).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT,
            c.editFact("agent", id, new UserMemoryController.ContentRequest("new text"), alice).getStatusCode());
        verify(agent, never()).listAllForSubject(anyString());
        verify(graph, never()).forgetFactText(anyString(), any(), anyString(), any());
    }
}
