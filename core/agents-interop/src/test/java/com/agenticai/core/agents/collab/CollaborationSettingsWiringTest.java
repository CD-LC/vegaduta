package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code delegation.maxBranches} and {@code delegation.branchTimeoutSeconds} on the real fan-out tools: the tools are built
 * with the real catalog entries over the real resolver and a store, run a real {@code call}, and the bound that applies is
 * the calling agent's, else its workspace's, else the platform's {@code app.agents.collaboration.*}. A pinned key ignores
 * every override, another workspace never sees an override, and the branches (other threads) keep the request's tenant.
 */
class CollaborationSettingsWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT = "tenant-a";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final CollaborationProperties platform = new CollaborationProperties();
    private InMemorySettingsStore store;
    private SettingsProperties settingsProperties;
    private DefaultSettingsResolver resolver;

    @BeforeEach
    void setUp() {
        store = new InMemorySettingsStore();
        settingsProperties = new SettingsProperties();
        settingsProperties.setCacheTtl(Duration.ZERO);
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void build() {
        resolver = new DefaultSettingsResolver(
                new SettingsRegistry(List.of(new CollaborationSettings(platform))),
                store, List.of(), settingsProperties, new MockEnvironment());
    }

    private void tenantOverride(String key, Object value) {
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", key, value, 1, "tester", Instant.now()));
    }

    private void agentOverride(UUID agent, String key, Object value) {
        store.saveCurrent(new SettingOverride(SettingScope.AGENT, agent.toString(), key, value, 1, "tester", Instant.now()));
    }

    private DelegateToTeamToolCallback team(UUID caller, TenantContext captured) {
        return new DelegateToTeamToolCallback(
                        chatService,
                        List.of(new DelegateToTeamToolCallback.Member(AGENT, "Researcher", "researcher")),
                        captured)
                .withLimits(platform)
                .withSettings(resolver, caller);
    }

    private RunSubtasksToolCallback subtasks(UUID caller, TenantContext captured) {
        return new RunSubtasksToolCallback(chatService, caller, captured, null, null, "chat-1")
                .withLimits(platform)
                .withSettings(resolver);
    }

    private static String teamTasks(int n) {
        StringBuilder sb = new StringBuilder("{\"tasks\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"agent\":\"researcher\",\"question\":\"q").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static String subtaskBriefs(int n) {
        StringBuilder sb = new StringBuilder("{\"tasks\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"brief\":\"part ").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static long count(JsonNode results, String status) {
        long n = 0;
        for (JsonNode r : results) {
            if (status.equals(r.get("status").asText())) {
                n++;
            }
        }
        return n;
    }

    private long ranTeam(DelegateToTeamToolCallback tool, int requested) throws Exception {
        return count(MAPPER.readTree(tool.call(teamTasks(requested))).get("results"), "ok");
    }

    private long ranSubtasks(RunSubtasksToolCallback tool, int requested) throws Exception {
        return count(MAPPER.readTree(tool.call(subtaskBriefs(requested))).get("results"), "ok");
    }

    private void answerQuickly() {
        when(chatService.delegateCall(any(), any(), any(), any())).thenReturn("x");
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenReturn("x");
    }

    private static int maxItems(String schema) throws Exception {
        return MAPPER.readTree(schema).get("properties").get("tasks").get("maxItems").asInt();
    }

    // ---------------------------------------------------------------- delegation.maxBranches

    @Test
    void withNoOverrideThePlatformBranchCapApplies() throws Exception {
        answerQuickly();
        build();

        assertEquals(8, ranTeam(team(AGENT, null), 10));
        assertEquals(8, ranSubtasks(subtasks(AGENT, null), 10));
    }

    @Test
    void aWorkspaceOverrideChangesHowManyBranchesRunOnBothTools() throws Exception {
        answerQuickly();
        build();
        tenantOverride(CollaborationSettings.MAX_BRANCHES, 3);

        assertEquals(3, ranTeam(team(AGENT, null), 10));
        assertEquals(3, ranSubtasks(subtasks(AGENT, null), 10));
        assertEquals(3, maxItems(team(AGENT, null).getToolDefinition().inputSchema()), "the offered schema says so too");
        assertEquals(3, maxItems(subtasks(AGENT, null).getToolDefinition().inputSchema()));
    }

    @Test
    void anAgentOverrideBeatsTheWorkspaceForThatAgentOnly() throws Exception {
        answerQuickly();
        build();
        tenantOverride(CollaborationSettings.MAX_BRANCHES, 5);
        agentOverride(AGENT, CollaborationSettings.MAX_BRANCHES, 2);

        assertEquals(2, ranTeam(team(AGENT, null), 10), "the calling agent's own value");
        assertEquals(2, ranSubtasks(subtasks(AGENT, null), 10));
        assertEquals(5, ranTeam(team(OTHER_AGENT, null), 10), "another agent: the workspace's value");
        assertEquals(5, ranSubtasks(subtasks(OTHER_AGENT, null), 10));
    }

    @Test
    void aPinnedKeyIgnoresTheWorkspaceAndAgentOverrides() throws Exception {
        answerQuickly();
        settingsProperties.getLocked().put(CollaborationSettings.MAX_BRANCHES, true);
        build();
        tenantOverride(CollaborationSettings.MAX_BRANCHES, 3);
        agentOverride(AGENT, CollaborationSettings.MAX_BRANCHES, 2);

        assertEquals(8, ranTeam(team(AGENT, null), 10));
        assertEquals(8, ranSubtasks(subtasks(AGENT, null), 10));
    }

    @Test
    void anotherWorkspacesOverrideNeverReachesThisOne() throws Exception {
        answerQuickly();
        build();
        TenantContext.set(TenantContext.of("tenant-b", "tester", Set.of()));
        tenantOverride(CollaborationSettings.MAX_BRANCHES, 2);
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));

        assertEquals(8, ranTeam(team(AGENT, null), 10));
    }

    @Test
    void theCapturedTenantAppliesWhenTheToolRunsOnAThreadWithNoContextAndTheThreadIsLeftAsFound() throws Exception {
        answerQuickly();
        build();
        tenantOverride(CollaborationSettings.MAX_BRANCHES, 3);
        TenantContext captured = TenantContext.current().orElseThrow();
        DelegateToTeamToolCallback tool = team(AGENT, captured);
        TenantContext.clear();

        assertEquals(3, ranTeam(tool, 10), "the request's tenant, captured at tool resolution");
        assertTrue(TenantContext.current().isEmpty(), "the calling thread's own (empty) context is restored");
    }

    @Test
    void aThreadWithNoTenantAtAllGetsThePlatformValue() throws Exception {
        answerQuickly();
        build();
        tenantOverride(CollaborationSettings.MAX_BRANCHES, 3);
        DelegateToTeamToolCallback tool = team(AGENT, null);
        TenantContext.clear();

        assertEquals(8, ranTeam(tool, 10));
    }

    @Test
    void aResolverThatCannotAnswerGivesThePlatformValue() throws Exception {
        answerQuickly();
        resolver = new DefaultSettingsResolver(
                new SettingsRegistry(List.of()), store, List.of(), settingsProperties, new MockEnvironment()); // keys unknown: throws

        assertEquals(8, ranTeam(team(AGENT, null), 10));
        assertEquals(8, ranSubtasks(subtasks(AGENT, null), 10));
    }

    @Test
    void theFactoryPassesTheCallingAgentOnSoItsOverrideBoundsTheTeamTool() throws Exception {
        answerQuickly();
        build();
        agentOverride(AGENT, CollaborationSettings.MAX_BRANCHES, 2);
        DefaultCollaboratorToolFactory factory = new DefaultCollaboratorToolFactory();
        factory.useCollaborationProperties(platform);
        factory.useSettings(resolver);
        List<CollaboratorToolFactory.TeamMember> members = List.of(
                new CollaboratorToolFactory.TeamMember(AGENT, "Researcher", "researcher"),
                new CollaboratorToolFactory.TeamMember(OTHER_AGENT, "Writer", "writer"));
        RunBudget budget = new RunBudget(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 1000);
        TenantContext captured = TenantContext.current().orElseThrow();

        var forAgent = factory.delegateToTeam(chatService, members, captured, budget, null, "s1",
                SwarmVerifierConfig.DISABLED, null, null, "goal", AGENT);
        var forOther = factory.delegateToTeam(chatService, members, captured, budget, null, "s1",
                SwarmVerifierConfig.DISABLED, null, null, "goal", OTHER_AGENT);
        var subtasks = factory.runSubtasks(chatService, AGENT, captured, budget, null, "s1");

        assertEquals(2, ranTeam((DelegateToTeamToolCallback) forAgent, 10));
        assertEquals(8, ranTeam((DelegateToTeamToolCallback) forOther, 10));
        assertEquals(2, ranSubtasks((RunSubtasksToolCallback) subtasks, 10));
    }

    // ---------------------------------------------------------------- delegation.branchTimeoutSeconds

    /** A branch that finishes after ~2.5 s, against a 1 s platform limit. */
    private CountDownLatch slowBranches() {
        CountDownLatch release = new CountDownLatch(1);
        when(chatService.delegateCall(any(), any(), any(), any())).thenAnswer(inv -> {
            release.await(2500, TimeUnit.MILLISECONDS);
            return "late";
        });
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            release.await(2500, TimeUnit.MILLISECONDS);
            return "late";
        });
        return release;
    }

    private static String statusOf(String json) throws Exception {
        return MAPPER.readTree(json).get("results").get(0).get("status").asText();
    }

    @Test
    void withNoOverrideThePlatformTimeoutCutsTheSlowBranch() throws Exception {
        CountDownLatch release = slowBranches();
        platform.setBranchTimeoutSeconds(1);
        build();

        assertEquals("timeout", statusOf(team(AGENT, null).call(teamTasks(1))));
        assertEquals("timeout", statusOf(subtasks(AGENT, null).call(subtaskBriefs(1))));
        release.countDown();
    }

    @Test
    void aWorkspaceTimeoutOverrideLetsTheSlowBranchFinish() throws Exception {
        CountDownLatch release = slowBranches();
        platform.setBranchTimeoutSeconds(1);
        build();
        tenantOverride(CollaborationSettings.BRANCH_TIMEOUT_SECONDS, 10);

        assertEquals("ok", statusOf(team(AGENT, null).call(teamTasks(1))));
        assertEquals("ok", statusOf(subtasks(AGENT, null).call(subtaskBriefs(1))));
        release.countDown();
    }

    @Test
    void anAgentTimeoutOverrideBeatsTheWorkspaceAndAppliesToThatAgentOnly() throws Exception {
        CountDownLatch release = slowBranches();
        platform.setBranchTimeoutSeconds(1);
        build();
        agentOverride(AGENT, CollaborationSettings.BRANCH_TIMEOUT_SECONDS, 10);

        assertEquals("ok", statusOf(team(AGENT, null).call(teamTasks(1))));
        assertEquals("timeout", statusOf(team(OTHER_AGENT, null).call(teamTasks(1))));
        assertEquals("ok", statusOf(subtasks(AGENT, null).call(subtaskBriefs(1))));
        assertEquals("timeout", statusOf(subtasks(OTHER_AGENT, null).call(subtaskBriefs(1))));
        release.countDown();
    }

    @Test
    void aPinnedTimeoutIgnoresTheOverrides() throws Exception {
        CountDownLatch release = slowBranches();
        platform.setBranchTimeoutSeconds(1);
        settingsProperties.getLocked().put(CollaborationSettings.BRANCH_TIMEOUT_SECONDS, true);
        build();
        tenantOverride(CollaborationSettings.BRANCH_TIMEOUT_SECONDS, 10);
        agentOverride(AGENT, CollaborationSettings.BRANCH_TIMEOUT_SECONDS, 10);

        assertEquals("timeout", statusOf(team(AGENT, null).call(teamTasks(1))));
        assertEquals("timeout", statusOf(subtasks(AGENT, null).call(subtaskBriefs(1))));
        release.countDown();
    }
}
