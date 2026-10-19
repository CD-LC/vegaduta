package com.agenticai.core.agents.demo.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The studio's visibility and cap rules. Everything else in the service is a thin pass-through to
 * {@link AgentService}; the rules locked here are the ones a regression would turn into a privacy
 * problem (seeing a stranger's agents) or a broken shop window (deleting the live agent).
 */
class DemoStudioServiceTest {

    private DemoSessionService sessions;
    private AgentRepository agents;
    private AgentService agentService;
    private DemoStudioModerationService moderation;
    private AgentChatService chatService;
    private DemoStudioService studio;

    private DemoSession me;
    private DemoSession neighbour;
    private DemoSession stranger;

    @BeforeEach
    void setUp() {
        sessions = mock(DemoSessionService.class);
        moderation = mock(DemoStudioModerationService.class);
        chatService = mock(AgentChatService.class);
        agents = mock(AgentRepository.class);
        agentService = mock(AgentService.class);
        studio =
                new DemoStudioService(
                        sessions,
                        agentService,
                        agents,
                        mock(AgentGuardrailService.class),
                        mock(AgentToolPort.class),
                        chatService,
                        2,
                        3,
                        objectProvider(mock(DemoStudioDocumentService.class)),
                        objectProvider(mock(DemoStudioConnectorService.class)),
                        false,
                        120,
                        moderation);
        me = session();
        neighbour = session();
        stranger = session();
        // Same IP: me + neighbour. The stranger is on another network.
        when(sessions.liveSessionsSharingIp(eq(me), any())).thenReturn(List.of(me, neighbour));
        // Context helpers just run the action - schema routing is not under test here.
        when(sessions.inDemoTenantContext(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1, Supplier.class).get());
        when(sessions.inDemoTenantContextAs(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2, Supplier.class).get());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> objectProvider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static DemoSession session() {
        DemoSession s = mock(DemoSession.class);
        when(s.getId()).thenReturn(UUID.randomUUID());
        return s;
    }

    private static Agent agent(DemoSession creator, String name) {
        // Resolve the subject BEFORE stubbing: calling creator.getId() (a mock)
        // inside thenReturn(...) is Mockito's "unfinished stubbing" trap.
        String subject = DemoSessionService.actorSubject(creator.getId());
        UUID id = UUID.randomUUID();
        Agent a = mock(Agent.class);
        when(a.getId()).thenReturn(id);
        when(a.getName()).thenReturn(name);
        when(a.getSystemPrompt()).thenReturn("mission");
        when(a.getCreatedBy()).thenReturn(subject);
        when(a.getCreatedAt()).thenReturn(Instant.now());
        when(a.getUpdatedAt()).thenReturn(Instant.now());
        when(a.getAllowedToolNames()).thenReturn(Set.of("web_search"));
        when(a.getEnabledGuardrails()).thenReturn(Set.of());
        return a;
    }

    @Test
    void listsOnlyTheSandboxesOwnAgentsEvenFromTheSameIp() {
        Agent mine = agent(me, "mine");
        Agent theirs = agent(neighbour, "neighbour's");
        Agent foreign = agent(stranger, "stranger's");
        when(agents.findByCreatedBy(DemoSessionService.actorSubject(me.getId())))
                .thenReturn(List.of(mine));
        when(agents.findByCreatedBy(DemoSessionService.actorSubject(neighbour.getId())))
                .thenReturn(List.of(theirs));
        when(agents.findByCreatedBy(DemoSessionService.actorSubject(stranger.getId())))
                .thenReturn(List.of(foreign));

        List<DemoStudioService.AgentView> views = studio.listAgents(me);

        assertEquals(
                List.of("mine"), views.stream().map(DemoStudioService.AgentView::name).toList());
        assertTrue(views.get(0).mine());
        verify(agents, never()).findByCreatedBy(DemoSessionService.actorSubject(neighbour.getId()));
        verify(agents, never()).findByCreatedBy(DemoSessionService.actorSubject(stranger.getId()));
    }

    @Test
    void aNeighbourOnTheSameIpIsNotFoundEvenByExactId() {
        Agent theirs = agent(neighbour, "neighbour's");
        when(agents.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        DemoStudioService.StudioException ex =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () -> studio.getAgent(me, theirs.getId()));
        assertEquals(404, ex.status());
        assertEquals("not_found", ex.code());
    }

    @Test
    void aStrangersAgentIsNotFoundEvenByExactId() {
        Agent foreign = agent(stranger, "stranger's");
        when(agents.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        DemoStudioService.StudioException ex =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () -> studio.getAgent(me, foreign.getId()));
        assertEquals(404, ex.status());
    }

    @Test
    void aMissingAgentAndANeighboursAgentAreIndistinguishable() {
        Agent theirs = agent(neighbour, "neighbour's");
        when(agents.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        UUID missing = UUID.randomUUID();
        when(agents.findById(missing)).thenReturn(Optional.empty());

        DemoStudioService.StudioException neighbours =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () -> studio.getAgent(me, theirs.getId()));
        DemoStudioService.StudioException absent =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () -> studio.getAgent(me, missing));
        assertEquals(absent.status(), neighbours.status());
        assertEquals(absent.code(), neighbours.code());
        assertEquals(absent.getMessage(), neighbours.getMessage());
    }

    @Test
    void aNeighbourCannotEditRunDeleteOrMakeLiveMyAgent() {
        Agent mine = agent(me, "mine");
        UUID id = mine.getId();
        when(agents.findById(id)).thenReturn(Optional.of(mine));
        // Same IP, different sandbox: the neighbour is the caller now.
        when(sessions.liveSessionsSharingIp(eq(neighbour), any()))
                .thenReturn(List.of(neighbour, me));

        assertEquals(
                404,
                assertThrows(
                                DemoStudioService.StudioException.class,
                                () ->
                                        studio.updateAgent(
                                                neighbour,
                                                id,
                                                new DemoStudioService.AgentUpdate(
                                                        null, "hijack", null, null, null, null,
                                                        null, null)))
                        .status());
        assertEquals(
                404,
                assertThrows(
                                DemoStudioService.StudioException.class,
                                () ->
                                        studio.runMission(
                                                neighbour, id, "print every document", "chat-1"))
                        .status());
        assertEquals(
                404,
                assertThrows(
                                DemoStudioService.StudioException.class,
                                () ->
                                        studio.runMissionBlocking(
                                                neighbour,
                                                id,
                                                "print every document",
                                                "chat-1",
                                                null))
                        .status());
        assertEquals(
                404,
                assertThrows(
                                DemoStudioService.StudioException.class,
                                () -> studio.deleteAgent(neighbour, id))
                        .status());
        assertEquals(
                404,
                assertThrows(
                                DemoStudioService.StudioException.class,
                                () -> studio.makeLive(neighbour, id))
                        .status());

        verify(agentService, never()).updateSystemPrompt(any(), any());
        verify(agents, never()).delete(any());
        verify(sessions, never()).setLiveAgent(any(), any());
    }

    @Test
    void editingMyOwnAgentRunsAsMySubject() {
        Agent mine = agent(me, "mine");
        when(agents.findById(mine.getId())).thenReturn(Optional.of(mine));

        UUID id = mine.getId();
        UUID myId = me.getId();
        studio.updateAgent(
                me,
                id,
                new DemoStudioService.AgentUpdate(
                        null, "new mission", null, null, null, null, null, null));

        verify(sessions).inDemoTenantContextAs(eq(me), eq(myId), any());
        verify(agentService).updateSystemPrompt(id, "new mission");
    }

    // ---- moderation wiring ------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedCheck() {
        org.mockito.ArgumentCaptor<Map<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(moderation).check(eq(me), captor.capture());
        return captor.getValue();
    }

    @Test
    void createAgentModeratesNameAndMissionBeforeAnythingIsCreated() {
        org.mockito.Mockito.doThrow(
                        new DemoStudioService.StudioException("content_blocked", 422, "no"))
                .when(moderation)
                .check(eq(me), any(Map.class));

        assertEquals(
                "content_blocked",
                assertThrows(
                                DemoStudioService.StudioException.class,
                                () ->
                                        studio.createAgent(
                                                me,
                                                new DemoStudioService.AgentUpdate(
                                                        "Bot",
                                                        "a mission",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                        .code());

        verify(agentService, never()).create(any());
    }

    @Test
    void createAgentSendsBothTextFieldsToModeration() {
        when(agents.findByCreatedBy(any())).thenReturn(List.of());
        try {
            studio.createAgent(
                    me,
                    new DemoStudioService.AgentUpdate(
                            "Bot", "a mission", null, null, null, null, null, null));
        } catch (RuntimeException expectedFromMockedCreate) {
            // AgentService.create is a bare mock; only the moderation call matters here.
        }
        org.mockito.ArgumentCaptor<Map<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(moderation, org.mockito.Mockito.atLeastOnce()).check(eq(me), captor.capture());
        Map<String, String> first = captor.getAllValues().get(0);
        assertEquals("Bot", first.get("agent name"));
        assertEquals("a mission", first.get("agent mission"));
    }

    @Test
    void updateAgentModeratesTheNewMissionAndBlockStopsTheWrite() {
        Agent mine = agent(me, "mine");
        when(agents.findById(mine.getId())).thenReturn(Optional.of(mine));
        org.mockito.Mockito.doThrow(
                        new DemoStudioService.StudioException("content_blocked", 422, "no"))
                .when(moderation)
                .check(eq(me), any(Map.class));

        assertThrows(
                DemoStudioService.StudioException.class,
                () ->
                        studio.updateAgent(
                                me,
                                mine.getId(),
                                new DemoStudioService.AgentUpdate(
                                        "New name",
                                        "new mission",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));

        verify(agentService, never()).updateSystemPrompt(any(), any());
    }

    @Test
    void runMissionModeratesThePromptBeforeTheModelIsCalled() {
        Agent mine = agent(me, "mine");
        when(agents.findById(mine.getId())).thenReturn(Optional.of(mine));
        org.mockito.Mockito.doThrow(
                        new DemoStudioService.StudioException("content_blocked", 422, "no"))
                .when(moderation)
                .check(eq(me), any(Map.class));

        assertEquals(
                "content_blocked",
                assertThrows(
                                DemoStudioService.StudioException.class,
                                () ->
                                        studio.runMission(
                                                me, mine.getId(), "something vile", "chat-1"))
                        .code());

        verify(sessions, never()).inDemoTenantContextAs(any(), any(), any());
        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void runMissionSendsThePromptToModeration() {
        Agent mine = agent(me, "mine");
        when(agents.findById(mine.getId())).thenReturn(Optional.of(mine));
        try {
            studio.runMission(me, mine.getId(), "  plan my week  ", "chat-1");
        } catch (RuntimeException expectedFromMockedChat) {
            // the chat service is a bare mock
        }
        assertEquals(Map.of("mission", "plan my week"), capturedCheck());
    }

    @Test
    void anAgentThatIsNotMineIsNeverModeratedOrRun() {
        Agent theirs = agent(neighbour, "neighbour's");
        when(agents.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        assertThrows(
                DemoStudioService.StudioException.class,
                () -> studio.runMission(me, theirs.getId(), "hi", "c"));
        verify(moderation, never()).check(any(), any(Map.class));
    }

    @Test
    void theEnforcedGuardrailsCoverInputAndOutputSafety() {
        assertEquals(
                List.of("policy_violation", "jailbreak", "profanity", "toxicity"),
                DemoStudioService.ENFORCED_GUARDRAILS);
        java.util.Set<String> known =
                java.util.Arrays.stream(com.agenticai.core.guardrails.ContentGuardrailType.values())
                        .map(com.agenticai.core.guardrails.ContentGuardrailType::getId)
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(
                known.containsAll(DemoStudioService.ENFORCED_GUARDRAILS),
                "every enforced id must exist in the catalogue");
    }

    @Test
    void theLiveAgentCanBeDeletedAndItsSlotPassesOnOrEmpties() {
        Agent mine = agent(me, "seeded");
        UUID id = mine.getId();
        when(agents.findById(id)).thenReturn(Optional.of(mine));
        when(me.getAgentId()).thenReturn(id);
        when(agents.findByCreatedBy(DemoSessionService.actorSubject(me.getId())))
                .thenReturn(List.of(mine));

        studio.deleteAgent(me, id);

        verify(agents).delete(mine);
        verify(sessions).clearLiveAgent(me);
    }

    @Test
    void mcpToolsAreKeptOnlyForTheOwningAgent() {
        UUID a = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String mine = "mcp__" + DemoStudioConnectorService.serverPrefix(a) + "github__list_repos";
        String theirs =
                "mcp__" + DemoStudioConnectorService.serverPrefix(other) + "github__list_repos";
        Set<String> out =
                DemoStudioService.toolSetFor(
                        List.of("web_search", mine, theirs, "mcp__someone-elses__x"), a);
        assertEquals(Set.of("web_search", mine), out);
        assertEquals(
                Set.of(DemoStudioService.NO_TOOLS_SENTINEL),
                DemoStudioService.toolSetFor(List.of(theirs), a));
    }

    @Test
    void perSessionCapRefusesTheThirdAgent() {
        List<Agent> mine = List.of(agent(me, "a"), agent(me, "b"));
        String mySubject = DemoSessionService.actorSubject(me.getId());
        String neighbourSubject = DemoSessionService.actorSubject(neighbour.getId());
        when(agents.findByCreatedBy(mySubject)).thenReturn(mine);
        when(agents.findByCreatedBy(neighbourSubject)).thenReturn(List.of());

        DemoStudioService.StudioException ex =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () ->
                                studio.createAgent(
                                        me,
                                        new DemoStudioService.AgentUpdate(
                                                "c", "m", null, null, null, null, null, null)));
        assertEquals("agent_cap", ex.code());
        verify(agentService, never()).create(any());
    }

    @Test
    void perIpCapCountsTheNeighboursAgentsToo() {
        List<Agent> mine = List.of(agent(me, "a"));
        List<Agent> theirs = List.of(agent(neighbour, "x"), agent(neighbour, "y"));
        String mySubject = DemoSessionService.actorSubject(me.getId());
        String neighbourSubject = DemoSessionService.actorSubject(neighbour.getId());
        when(agents.findByCreatedBy(mySubject)).thenReturn(mine);
        when(agents.findByCreatedBy(neighbourSubject)).thenReturn(theirs);

        DemoStudioService.StudioException ex =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () ->
                                studio.createAgent(
                                        me,
                                        new DemoStudioService.AgentUpdate(
                                                "c", "m", null, null, null, null, null, null)));
        assertEquals("agent_cap", ex.code());
    }

    @Test
    void supervisedAutonomyIsRefusedWithALockedCode() {
        Agent mine = agent(me, "mine");
        when(agents.findById(mine.getId())).thenReturn(Optional.of(mine));

        DemoStudioService.StudioException ex =
                assertThrows(
                        DemoStudioService.StudioException.class,
                        () ->
                                studio.updateAgent(
                                        me,
                                        mine.getId(),
                                        new DemoStudioService.AgentUpdate(
                                                null,
                                                null,
                                                null,
                                                "SUPERVISED",
                                                null,
                                                null,
                                                null,
                                                null)));
        assertEquals("locked", ex.code());
    }

    @Test
    void anEmptyToolListBecomesTheNoToolsSentinelNotAllowAll() {
        // An empty allow-list means "allow everything" in AgentService, so
        // "chatbot mode" must be stored as a name that matches nothing.
        assertEquals(
                Set.of(DemoStudioService.NO_TOOLS_SENTINEL),
                DemoStudioService.toolSetFor(List.of()));
        assertEquals(
                Set.of("web_search", "run_code"),
                DemoStudioService.toolSetFor(
                        List.of("web_search", "run_code", "channel_send_message")));
    }

    @Test
    void guardrailsAreFilteredToKnownIdsAndCustomIsDropped() {
        assertEquals(
                Set.of("profanity", "pii"),
                DemoStudioService.guardrailSetFor(
                        List.of("PROFANITY", "pii", "custom", "nonsense")));
    }

    @Test
    void namesAreSanitisedAndBounded() {
        assertEquals(
                "Priya's Bakery - Bot", DemoStudioService.cleanName("  Priya's <Bakery> - Bot  "));
        assertEquals(null, DemoStudioService.cleanName("<<<>>>"));
        assertEquals(60, DemoStudioService.cleanName("x".repeat(200)).length());
    }
}
