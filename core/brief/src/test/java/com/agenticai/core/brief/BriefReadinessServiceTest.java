package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.knowledge.KnowledgeDocumentService;
import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistryEnvVarSpec;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.secrets.SecretScope;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.secrets.TenantSecret;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.WorkflowService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Collaborators are mocked; every assertion is on the rows a caller would actually see. */
class BriefReadinessServiceTest {

    private final SecretVaultService vault = mock(SecretVaultService.class);
    private final OfficialMcpRegistryClient registry = mock(OfficialMcpRegistryClient.class);
    private final McpCatalogService catalog = mock(McpCatalogService.class);
    private final KnowledgeCollectionService collections = mock(KnowledgeCollectionService.class);
    private final KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
    private final ChannelSessionService channels = mock(ChannelSessionService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final AgentService agentService = mock(AgentService.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private BriefReadinessService service;

    @BeforeEach
    void setUp() {
        service = new BriefReadinessService(vault, registry, catalog, collections, documents, channels, limits,
            agentService, workflowService);
        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        when(limits.maxAgents("t1")).thenReturn(Optional.empty());
        when(limits.maxWorkflows("t1")).thenReturn(Optional.empty());
        when(limits.maxKbCollections("t1")).thenReturn(Optional.empty());
        asUser(Set.of("PERM_MANAGE_AGENTS", "PERM_MANAGE_WORKFLOWS_ADMIN", "PERM_MANAGE_INTEGRATIONS"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void asUser(Set<String> authorities) {
        TenantContext.set(TenantContext.of("t1", "user-1", authorities));
    }

    private static TenantSecret secret(String name, SecretScope scope, boolean resolvable, String owner) {
        TenantSecret s = mock(TenantSecret.class);
        when(s.getName()).thenReturn(name);
        when(s.getScope()).thenReturn(scope);
        when(s.isResolvable()).thenReturn(resolvable);
        when(s.getOwnerUserId()).thenReturn(owner);
        return s;
    }

    /** Built before the when(...) call: creating a mock (which stubs it) inside another stubbing is illegal in Mockito. */
    private void stubVault(TenantSecret... secrets) {
        when(vault.listVisibleTo("user-1")).thenReturn(List.of(secrets));
    }

    private static ReadinessRow row(List<ReadinessRow> rows, String kind, String ref) {
        return rows.stream().filter(r -> r.kind().equals(kind) && ref.equals(r.ref())).findFirst()
            .orElseThrow(() -> new AssertionError("no " + kind + " row for " + ref + " in " + rows));
    }

    @Test
    void secretsAreReportedByNameOnlyAndTheVaultPlaintextExitIsNeverCalled() {
        stubVault(secret("TALLY_API_KEY", SecretScope.TENANT, true, null));
        List<ReadinessRow> rows = service.compute(BriefFixtures.valid().build());

        ReadinessRow r = row(rows, "SECRET", "s1");
        assertThat(r.state()).isEqualTo("READY");
        assertThat(r.label()).isEqualTo("TALLY_API_KEY");
        verify(vault, never()).resolveForRun(anyString(), anyString());
        verify(vault, never()).resolveForCodeTool(anyString(), anyString());
    }

    @Test
    void missingSecretDeepLinksToTheSecretsFormAndAdminOnlyScopeWaitsOnAnAdmin() {
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        ReadinessRow missing = row(service.compute(BriefFixtures.valid().build()), "SECRET", "s1");
        assertThat(missing.state()).isEqualTo("MISSING");
        assertThat(missing.actionHref()).isEqualTo("/secrets?tour=field:secret-add&name=TALLY_API_KEY");

        asUser(Set.of("PERM_MANAGE_AGENTS"));
        ReadinessRow waiting = row(service.compute(BriefFixtures.valid().build()), "SECRET", "s1");
        assertThat(waiting.state()).isEqualTo("WAITING_ON_ADMIN");
        assertThat(waiting.reason()).contains("Manage integrations");
    }

    @Test
    void revokedOrExpiredSecretIsNotReady() {
        stubVault(secret("TALLY_API_KEY", SecretScope.TENANT, false, null));
        ReadinessRow r = row(service.compute(BriefFixtures.valid().build()), "SECRET", "s1");
        assertThat(r.state()).isEqualTo("MISSING");
        assertThat(r.reason()).contains("revoked or expired");
    }

    @Test
    void userScopedSecretOnlyCountsWhenTheCallerOwnsIt() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.secrets = new java.util.ArrayList<>(List.of(new Brief.SecretSpec("s1", "MY_TOKEN", "USER", "Personal token", null)));
        stubVault(secret("MY_TOKEN", SecretScope.USER, true, "someone-else"));
        assertThat(row(service.compute(b.build()), "SECRET", "s1").state()).isEqualTo("MISSING");
        stubVault(secret("MY_TOKEN", SecretScope.USER, true, "user-1"));
        assertThat(row(service.compute(b.build()), "SECRET", "s1").state()).isEqualTo("READY");
    }

    private Brief withMcp() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.secrets = new java.util.ArrayList<>(List.of(new Brief.SecretSpec("s1", "GITHUB_TOKEN", "TENANT", "why", null)));
        b.mcp = new java.util.ArrayList<>(List.of(new Brief.McpServerSpec("m1", "io.github.acme/gh", "GitHub",
            Map.of("GITHUB_TOKEN", new Brief.EnvBinding("s1")))));
        b.perNode = BriefFixtures.narrative("a1", "w1", "k1", "s1", "m1", "w1.n3");
        b.narrative = new Brief.Narrative("why", b.perNode);
        return b.build();
    }

    private void registryHas(String name, RegistryEnvVarSpec... env) {
        RegistrySearchResult result = new RegistrySearchResult(name, "GitHub", "desc", "1.0", null, McpServerType.STDIO,
            "npx", true, null, "npx", List.of(), null, List.of(env), "active", false);
        when(registry.search(anyString(), any())).thenReturn(new RegistrySearchResponse(List.of(result), null, false));
    }

    @Test
    void mcpServerNeedingAnUnstoredSecretIsMissingAndNamesTheVariable() {
        registryHas("io.github.acme/gh", new RegistryEnvVarSpec("GITHUB_TOKEN", "token", true, true, null, false));
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        ReadinessRow r = row(service.compute(withMcp()), "CONNECTOR", "m1");
        assertThat(r.state()).isEqualTo("MISSING");
        assertThat(r.reason()).contains("GITHUB_TOKEN");
    }

    @Test
    void mcpServerWithItsSecretStoredIsReadyForAdminsAndWaitsOtherwise() {
        registryHas("io.github.acme/gh", new RegistryEnvVarSpec("GITHUB_TOKEN", "token", true, true, null, false));
        stubVault(secret("GITHUB_TOKEN", SecretScope.TENANT, true, null));
        assertThat(row(service.compute(withMcp()), "CONNECTOR", "m1").state()).isEqualTo("READY");

        asUser(Set.of("PERM_MANAGE_AGENTS"));
        assertThat(row(service.compute(withMcp()), "CONNECTOR", "m1").state()).isEqualTo("WAITING_ON_ADMIN");
    }

    @Test
    void unreachableRegistryIsSaidPlainlyNotGuessedAsReady() {
        when(registry.search(anyString(), any())).thenReturn(new RegistrySearchResponse(List.of(), null, true));
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        ReadinessRow r = row(service.compute(withMcp()), "CONNECTOR", "m1");
        assertThat(r.state()).isEqualTo("MISSING");
        assertThat(r.reason()).contains("could not be reached");
    }

    @Test
    void customMcpServerOnTheFreePlanIsGatedAndNamesTheUnlockingPlan() {
        when(limits.plan("t1")).thenReturn(TenantPlan.FREE);
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        ReadinessRow r = row(service.compute(withMcp()), "CONNECTOR", "m1");
        assertThat(r.state()).isEqualTo("GATED");
        assertThat(r.unlocksAt()).isEqualTo("PREMIUM");
    }

    @Test
    void evalSetOnFreePlanIsAGatedPlanLimitRow() {
        when(limits.plan("t1")).thenReturn(TenantPlan.FREE);
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        List<ReadinessRow> rows = service.compute(BriefFixtures.valid().build());
        ReadinessRow gate = rows.stream().filter(r -> r.kind().equals("PLAN_LIMIT") && r.label().contains("Eval")).findFirst().orElseThrow();
        assertThat(gate.state()).isEqualTo("GATED");
        assertThat(gate.unlocksAt()).isEqualTo("PREMIUM");
    }

    @Test
    void agentCapCountsExistingPlusNewAndSaysWhatItWouldBecome() {
        when(limits.maxAgents("t1")).thenReturn(Optional.of(2));
        when(agentService.listAll()).thenReturn(List.of(mock(com.agenticai.core.agents.dto.AgentResponse.class),
            mock(com.agenticai.core.agents.dto.AgentResponse.class)));
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        ReadinessRow cap = service.compute(BriefFixtures.valid().build()).stream()
            .filter(r -> r.kind().equals("PLAN_LIMIT") && r.label().contains("agents")).findFirst().orElseThrow();
        assertThat(cap.state()).isEqualTo("GATED");
        assertThat(cap.reason()).contains("3 agents").contains("limit of 2");
        assertThat(cap.unlocksAt()).isEqualTo("UNLIMITED");
    }

    @Test
    void knowledgeIsMissingBeforeApplyAndReportsTheRealFileCountAfter() {
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        Brief brief = BriefFixtures.valid().build();
        ReadinessRow before = row(service.compute(brief), "KNOWLEDGE", "k1");
        assertThat(before.state()).isEqualTo("MISSING");
        assertThat(before.reason()).contains("Upload your terms.");

        UUID collectionId = UUID.randomUUID();
        when(documents.fileCount(collectionId)).thenReturn(0L);
        assertThat(row(service.compute(brief, Map.of("k1", collectionId.toString())), "KNOWLEDGE", "k1").reason()).contains("empty");
        when(documents.fileCount(collectionId)).thenReturn(3L);
        ReadinessRow populated = row(service.compute(brief, Map.of("k1", collectionId.toString())), "KNOWLEDGE", "k1");
        assertThat(populated.state()).isEqualTo("READY");
        assertThat(populated.reason()).contains("3 files");
    }

    @Test
    void channelRowsReflectRealRoutingAndUnmappedKinds() {
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        BriefFixtures.B b = BriefFixtures.valid();
        b.channels = new java.util.ArrayList<>(List.of(
            new Brief.ChannelSpec("c1", "WHATSAPP", "a1", true),
            new Brief.ChannelSpec("c2", "SMS", "a1", true),
            new Brief.ChannelSpec("c3", "WEB", "a1", true),
            new Brief.ChannelSpec("c4", "TELEGRAM", "a1", true)));
        b.perNode = BriefFixtures.narrative("a1", "w1", "k1", "s1", "c1", "c2", "c3", "c4", "w1.n3");
        b.narrative = new Brief.Narrative("why", b.perNode);

        ChannelDefaultAgent taken = mock(ChannelDefaultAgent.class);
        when(taken.getAgentId()).thenReturn(UUID.randomUUID());
        when(channels.routingFor(ChannelType.WHATSAPP)).thenReturn(Optional.of(taken));
        when(channels.routingFor(ChannelType.TELEGRAM)).thenReturn(Optional.empty());

        List<ReadinessRow> rows = service.compute(b.build());
        assertThat(row(rows, "CHANNEL", "c1").state()).isEqualTo("MISSING");
        assertThat(row(rows, "CHANNEL", "c1").reason()).contains("already routed");
        // SMS is a real ChannelType since the SMS channel shipped: with no default agent it is READY.
        assertThat(row(rows, "CHANNEL", "c2").state()).isEqualTo("READY");
        assertThat(row(rows, "CHANNEL", "c3").state()).isEqualTo("READY"); // WEB: no channel setup
        assertThat(row(rows, "CHANNEL", "c4").state()).isEqualTo("READY");
    }

    @Test
    void permissionRowsAppearForWhatTheCallerLacks() {
        when(vault.listVisibleTo("user-1")).thenReturn(List.of());
        asUser(Set.of());
        List<ReadinessRow> rows = service.compute(BriefFixtures.valid().build());
        List<String> labels = rows.stream().filter(r -> r.kind().equals("PERMISSION")).map(ReadinessRow::label).toList();
        assertThat(labels).containsExactlyInAnyOrder("PERM_MANAGE_INTEGRATIONS", "PERM_MANAGE_AGENTS", "PERM_MANAGE_WORKFLOWS_ADMIN");
        assertThat(rows.stream().filter(r -> r.kind().equals("PERMISSION")).map(ReadinessRow::state)).containsOnly("WAITING_ON_ADMIN");
    }

    @Test
    void clarificationOnlyBriefHasNoReadiness() {
        Brief asking = new Brief(1, "Something", "Chase invoices somehow please", "en",
            List.of(new Brief.Clarification("Which system?", List.of())), null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null);
        assertThat(service.compute(asking)).isEmpty();
    }

    @Test
    void summaryCountsEveryState() {
        Map<String, Integer> s = service.summarize(List.of(
            new ReadinessRow("SECRET", "s1", "A", "READY", "r", null, null),
            new ReadinessRow("SECRET", "s2", "B", "MISSING", "r", null, null),
            new ReadinessRow("SECRET", "s3", "C", "MISSING", "r", null, null)));
        assertThat(s).containsEntry("READY", 1).containsEntry("MISSING", 2).containsEntry("GATED", 0);
    }
}
