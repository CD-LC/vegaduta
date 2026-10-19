package com.agenticai.core.agents.demo.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Two things the product owner asked for, tested where they are enforced outside {@link
 * DemoStudioService}: workflow and run isolation between sandboxes that share a public address, and
 * content moderation on the document, connector-search and workflow entry points.
 */
class DemoStudioIsolationAndEntryPointsTest {

    private DemoSessionService sessions;
    private DemoStudioService studio;
    private DemoStudioWorkflowRunner runner;
    private DemoStudioWorkflowRepository workflows;
    private DemoStudioRunRepository runs;
    private DemoStudioFairUse fairUse;
    private DemoStudioController controller;

    private DemoSession a;
    private DemoSession b;
    private DemoStudioWorkflow workflowOfB;
    private DemoStudioRun runOfB;

    @BeforeEach
    void setUp() throws Exception {
        sessions = mock(DemoSessionService.class);
        studio = mock(DemoStudioService.class);
        runner = mock(DemoStudioWorkflowRunner.class);
        workflows = mock(DemoStudioWorkflowRepository.class);
        runs = mock(DemoStudioRunRepository.class);
        fairUse = mock(DemoStudioFairUse.class);
        a = session("tokA");
        b = session("tokB");
        // A and B share a public address - the case that used to make everything visible.
        when(sessions.liveSessionsSharingIp(any(), any()))
                .thenAnswer(
                        inv -> List.of(inv.getArgument(0), a.equals(inv.getArgument(0)) ? b : a));

        controller =
                new DemoStudioController(
                        sessions,
                        studio,
                        runner,
                        workflows,
                        runs,
                        new ObjectMapper(),
                        mock(DemoStudioDocumentService.class),
                        mock(DemoStudioConnectorService.class),
                        fairUse,
                        10);

        workflowOfB =
                new DemoStudioWorkflow(
                        b.getId(), "203.0.113.9", "B's flow", "{\"steps\":[]}", Instant.now());
        runOfB = new DemoStudioRun(UUID.randomUUID(), b.getId(), "input", Instant.now());
        setId(runOfB, UUID.randomUUID());
        setId(workflowOfB, UUID.randomUUID());
        when(workflows.findById(workflowOfB.getId())).thenReturn(Optional.of(workflowOfB));
        when(runs.findById(runOfB.getId())).thenReturn(Optional.of(runOfB));
    }

    private DemoSession session(String token) {
        DemoSession s = mock(DemoSession.class);
        when(s.getId()).thenReturn(UUID.randomUUID());
        when(sessions.resolve(token)).thenReturn(Optional.of(s));
        return s;
    }

    private static void setId(Object entity, UUID id) throws Exception {
        java.lang.reflect.Field f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    private static void assertNotFound(Runnable call, String messageFragment) {
        StudioException ex = assertThrows(StudioException.class, call::run);
        assertEquals(404, ex.status());
        assertEquals("not_found", ex.code());
        assertTrue(ex.getMessage().contains(messageFragment), ex.getMessage());
    }

    // ---- workflow and run isolation ---------------------------------------------------------

    @Test
    void aSandboxListsOnlyItsOwnWorkflows() {
        DemoStudioWorkflow mine =
                new DemoStudioWorkflow(
                        a.getId(), "203.0.113.9", "A's flow", "{\"steps\":[]}", Instant.now());
        when(workflows.findBySessionIdInOrderByCreatedAtAsc(List.of(a.getId())))
                .thenReturn(List.of(mine));

        List<DemoStudioController.WorkflowView> views = controller.listWorkflows("tokA");

        assertEquals(
                List.of("A's flow"),
                views.stream().map(DemoStudioController.WorkflowView::name).toList());
        assertTrue(views.get(0).mine());
        verify(workflows, never()).findBySessionIdInOrderByCreatedAtAsc(List.of(b.getId()));
        verify(workflows, never())
                .findBySessionIdInOrderByCreatedAtAsc(List.of(a.getId(), b.getId()));
    }

    @Test
    void aNeighbourCannotListRunEditDeleteOrReadRunsOfMyWorkflowEvenFromTheSameIp() {
        UUID id = workflowOfB.getId();
        String fragment = "That workflow isn't in your studio.";

        assertNotFound(() -> controller.listRuns("tokA", id), fragment);
        assertNotFound(() -> controller.deleteWorkflow("tokA", id), fragment);
        assertNotFound(
                () -> controller.runWorkflow("tokA", id, new DemoStudioController.RunRequest("hi")),
                fragment);
        assertNotFound(
                () ->
                        controller.updateWorkflow(
                                "tokA",
                                id,
                                new DemoStudioController.WorkflowRequest(
                                        "renamed",
                                        new DemoStudioWorkflowRunner.Definition(List.of()))),
                fragment);

        verify(workflows, never()).delete(any());
        verify(workflows, never()).save(any());
        verify(runner, never()).run(any(), any(), any());
        verify(runs, never()).findTop10ByWorkflowIdOrderByStartedAtDesc(any());
    }

    @Test
    void aMissingWorkflowAndAnotherSandboxsWorkflowAreIndistinguishable() {
        StudioException foreign =
                assertThrows(
                        StudioException.class,
                        () -> controller.listRuns("tokA", workflowOfB.getId()));
        StudioException missing =
                assertThrows(
                        StudioException.class,
                        () -> controller.listRuns("tokA", UUID.randomUUID()));
        assertEquals(missing.status(), foreign.status());
        assertEquals(missing.code(), foreign.code());
        assertEquals(missing.getMessage(), foreign.getMessage());
    }

    @Test
    void aNeighbourCannotApproveOrRejectMyRun() {
        assertNotFound(
                () ->
                        controller.decide(
                                "tokA",
                                runOfB.getId(),
                                new DemoStudioController.DecisionRequest(true)),
                "That run isn't in your studio.");
        verify(runner, never()).decide(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void theOwnerCanStillDecideTheirRun() {
        when(runner.decide(runOfB.getId(), true)).thenReturn(true);
        assertEquals(
                true,
                controller
                        .decide(
                                "tokB",
                                runOfB.getId(),
                                new DemoStudioController.DecisionRequest(true))
                        .getBody()
                        .get("accepted"));
    }

    // ---- workflow moderation wiring ---------------------------------------------------------

    @Test
    void createWorkflowModeratesBeforeSaving() {
        DemoStudioWorkflowRunner.Definition def =
                new DemoStudioWorkflowRunner.Definition(List.of());
        when(runner.validate(a, def)).thenReturn(def);
        doThrow(new StudioException("content_blocked", 422, "no"))
                .when(runner)
                .moderate(a, "Bad name", def);

        assertEquals(
                "content_blocked",
                assertThrows(
                                StudioException.class,
                                () ->
                                        controller.createWorkflow(
                                                "tokA",
                                                new DemoStudioController.WorkflowRequest(
                                                        "Bad name", def)))
                        .code());

        verify(workflows, never()).save(any());
    }

    @Test
    void updateWorkflowModeratesBeforeSaving() {
        DemoStudioWorkflow mine =
                new DemoStudioWorkflow(
                        a.getId(), "203.0.113.9", "A's flow", "{\"steps\":[]}", Instant.now());
        UUID id = UUID.randomUUID();
        try {
            setId(mine, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(workflows.findById(id)).thenReturn(Optional.of(mine));
        DemoStudioWorkflowRunner.Definition def =
                new DemoStudioWorkflowRunner.Definition(List.of());
        when(runner.validate(a, def)).thenReturn(def);
        doThrow(new StudioException("content_blocked", 422, "no"))
                .when(runner)
                .moderate(a, "New name", def);

        assertThrows(
                StudioException.class,
                () ->
                        controller.updateWorkflow(
                                "tokA",
                                id,
                                new DemoStudioController.WorkflowRequest("New name", def)));

        verify(workflows, never()).save(any());
    }

    // ---- documents and connector search -----------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(value);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private DemoStudioDocumentService documentService(DemoStudioModerationService moderation) {
        return new DemoStudioDocumentService(
                provider(mock(com.agenticai.core.knowledge.KnowledgeCollectionService.class)),
                provider(mock(com.agenticai.core.knowledge.KnowledgeDocumentService.class)),
                provider(mock(com.agenticai.core.knowledge.KnowledgeIngestionService.class)),
                studio,
                sessions,
                mock(AgentService.class),
                5,
                409_600L,
                false,
                moderation);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentUploadModeratesNameAndTextBeforeAnythingIsStored() {
        UUID agentId = UUID.randomUUID();
        when(studio.requireVisible(a, agentId))
                .thenReturn(
                        new DemoStudioService.Owned(
                                mock(com.agenticai.core.agents.core.Agent.class), a));
        when(sessions.inDemoTenantContextAs(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2, Supplier.class).get());
        DemoStudioModerationService moderation = mock(DemoStudioModerationService.class);
        doThrow(new StudioException("content_blocked", 422, "no"))
                .when(moderation)
                .check(eq(a), any(Map.class));
        DemoStudioDocumentService documents = documentService(moderation);

        StudioException ex =
                assertThrows(
                        StudioException.class,
                        () ->
                                documents.upload(
                                        a,
                                        agentId,
                                        "notes.txt",
                                        "some pasted text"
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("content_blocked", ex.code());
        org.mockito.ArgumentCaptor<Map<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(moderation).check(eq(a), captor.capture());
        assertEquals("notes.txt", captor.getValue().get("document name"));
        assertEquals("some pasted text", captor.getValue().get("document text"));
        verify(sessions, never()).inDemoTenantContextAs(any(), any(), any());
    }

    @Test
    void aNeighbourCannotUploadToOrListMyAgentsDocuments() {
        UUID agentId = UUID.randomUUID();
        when(studio.requireVisible(a, agentId))
                .thenThrow(
                        new StudioException("not_found", 404, "That agent isn't in your studio."));
        DemoStudioModerationService moderation = mock(DemoStudioModerationService.class);
        DemoStudioDocumentService documents = documentService(moderation);

        assertEquals(
                404,
                assertThrows(
                                StudioException.class,
                                () -> documents.upload(a, agentId, "x.txt", new byte[] {1}))
                        .status());
        assertEquals(
                404,
                assertThrows(StudioException.class, () -> documents.list(a, agentId)).status());
        assertEquals(
                404,
                assertThrows(
                                StudioException.class,
                                () -> documents.delete(a, agentId, UUID.randomUUID()))
                        .status());
        verify(moderation, never()).check(any(), any(Map.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void registrySearchModeratesTheQueryBeforeCallingTheRegistry() {
        com.agenticai.core.mcp.registry.OfficialMcpRegistryClient client =
                mock(com.agenticai.core.mcp.registry.OfficialMcpRegistryClient.class);
        DemoStudioModerationService moderation = mock(DemoStudioModerationService.class);
        DemoStudioConnectorService connectors =
                new DemoStudioConnectorService(
                        provider(client),
                        provider(null),
                        provider(null),
                        provider(null),
                        studio,
                        sessions,
                        mock(AgentService.class),
                        mock(com.agenticai.core.agents.tools.AgentToolPort.class),
                        false,
                        3,
                        moderation);
        doThrow(new StudioException("content_blocked", 422, "no"))
                .when(moderation)
                .check(eq(a), any(Map.class));

        assertEquals(
                "content_blocked",
                assertThrows(StudioException.class, () -> connectors.search(a, "porn", null))
                        .code());

        verify(moderation).check(a, Map.of("registry search", "porn"));
        verify(client, never()).search(anyString(), any());
    }
}
