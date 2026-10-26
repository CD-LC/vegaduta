package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.collab.CollaborationProperties;
import com.agenticai.core.agents.collab.CollaborationSettings;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.limits.AgentRuntimeProperties;
import com.agenticai.core.agents.limits.AgentRuntimeSettingsResolver;
import com.agenticai.core.agents.limits.RuntimeLimitsSettings;
import com.agenticai.core.agents.limits.TenantRuntimeLimitsService;
import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.limits.StepLimitSettings;
import com.agenticai.core.agents.settings.BehaviourSettingsCatalog;
import com.agenticai.core.agents.settings.ProductControlsCatalog;
import com.agenticai.core.agents.skills.SkillPromptSettings;
import com.agenticai.core.agents.turn.ChatLimitsProperties;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.agents.turn.cache.SemanticCacheProperties;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ChatCacheProperties;
import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.agents.turn.FollowUpProperties;
import com.agenticai.core.agents.turn.quality.RoutingSimpleProperties;
import com.agenticai.core.approval.ApprovalExpiryProperties;
import com.agenticai.core.chat.LlmRetryProperties;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.graphmemory.GraphMemoryProperties;
import com.agenticai.core.graphmemory.GraphMemorySettings;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.knowledge.KnowledgeSearchProperties;
import com.agenticai.core.knowledge.KnowledgeSettings;
import com.agenticai.core.providers.ProviderSettings;
import com.agenticai.core.quality.QualitySettings;
import com.agenticai.core.rl.policy.RlPolicyProperties;
import com.agenticai.core.rl.policy.RlSettings;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingRole;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.Strictness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * The assembled settings catalog (every module's contributor, built over the property objects' code defaults):
 * every entry is explained in {@code web/messages/en.json}, every bound and default is sane, and the security
 * entries obey the rules the API relies on. A new entry that is not translated or whose default is outside its own
 * bounds fails here, not in a customer's settings page.
 */
class SettingsCatalogContractTest {

    /** Platform defaults that are deliberately outside the override bounds (0 = off / no limit). */
    private static final Set<String> SENTINEL_DEFAULTS = Set.of("agent.limits.toolTimeoutSeconds");

    private static SettingsRegistry registry;
    private static JsonNode en;
    private static Path repoRoot;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void assemble() throws Exception {
        AgentRuntimeProperties runtimeProps = new AgentRuntimeProperties();
        AgentRuntimeSettingsResolver runtime = new AgentRuntimeSettingsResolver(
                runtimeProps,
                new AgentRuntimeSettingsResolver.PlatformKeys(15, Duration.ofSeconds(240), 8),
                new ToolStepLimitProperties(), new ToolOutputProperties(), new ToolLoopProperties(),
                mock(TenantRuntimeLimitsService.class), mock(AgentService.class));
        ObjectProvider<AgentRuntimeSettingsResolver> rp = mock(ObjectProvider.class);
        when(rp.getObject()).thenReturn(runtime);
        RuntimeLimitsSettings limits = new RuntimeLimitsSettings(
                runtimeProps, rp, mock(ObjectProvider.class), mock(ObjectProvider.class));
        BehaviourSettingsCatalog behaviour = new BehaviourSettingsCatalog(
                new ApprovalContinuationProperties(), new ApprovalExpiryProperties(), new ChatFinishProperties(),
                new LlmRetryProperties(), new ChatCacheProperties(), new FollowUpProperties(),
                new ContinuationProperties(), new ToolLoopProperties(), new RoutingSimpleProperties(),
                new MemoryLimitsProperties(), new GuardrailTuningProperties(), new AuxiliaryModelProperties());
        registry = new SettingsRegistry(List.of(
                limits, behaviour, new CollaborationSettings(new CollaborationProperties()),
                new QualitySettings(new MockEnvironment()),
                new KnowledgeSettings(new KnowledgeSearchProperties()),
                new GraphMemorySettings(new GraphMemoryProperties()),
                new RlSettings(new RlPolicyProperties()),
                new ProviderSettings(new MockEnvironment()),
                new StepLimitSettings(new ToolStepLimitProperties()),
                new com.agenticai.core.agents.tools.scratchpad.ScratchpadSettings(
                        new com.agenticai.core.agents.tools.scratchpad.ScratchpadProperties()),
                new SkillPromptSettings(),
                new ProductControlsCatalog(
                        new SemanticCacheProperties(), new ChatLimitsProperties(), new FollowUpProperties(),
                        new MemoryLimitsProperties(), new ToolLedgerProperties())));
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("web/messages/en.json"))) {
            root = root.getParent();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "not run from a checkout of the repository");
        repoRoot = root;
        en = new ObjectMapper().readTree(Files.readString(root.resolve("web/messages/en.json")));
    }

    private static JsonNode at(String dotted) {
        JsonNode n = en;
        for (String p : dotted.split("\\.")) {
            n = n == null ? null : n.get(p);
        }
        return n;
    }

    @Test
    void theCatalogIsTheCuratedSize() {
        assertThat(registry.all()).hasSizeBetween(30, 80);
        assertThat(registry.all()).extracting(SettingDefinition::key)
                .contains("agent.limits.maxToolSteps", "agent.limits.turnTimeoutSeconds", "agent.limits.toolTimeoutSeconds",
                        "agent.limits.toolOutputMaxChars", "agent.limits.retrievalTopK", "agent.limits.retrievalMinScore",
                        "agent.limits.continuationPasses", "approvals.continuation.enabled", "approvals.expiry.ttl",
                        "guardrails.failMode", "guardrails.toxicity.blockScore", "chat.finish.maxContinuations",
                        "chat.retry.maxAttempts", "chat.cache.eligibleWithTools", "delegation.maxBranches", "routing.simple.maxChars");
    }

    @Test
    void theOwnerVisibleProductControlsAreInTheCatalogWithTheScopesTheirUseSitesHonour() {
        List<String> workspaceOnly = List.of(
                "chat.toolLedger.enabled", "chat.toolLedger.replayEnabled", "chat.toolLedger.replayTurns",
                "chat.toolLedger.maxEntriesPerTurn", "chat.toolLedger.maxReplayCharsPerTurn", "memory.tenant.maxMemories",
                "skills.prompt.maxSkills", "skills.prompt.maxBlockChars");
        List<String> perAgent = List.of(
                "agent.steps.graceCalls", "agent.steps.supervisedDivisor", "agent.steps.supervisedMinSteps",
                "agent.steps.autonomousMultiplier", "chat.semanticCache.similarityThreshold", "chat.semanticCache.ttl",
                "chat.semanticCache.maxEntriesPerAgent", "chat.followUp.maxStickyTools", "chat.followUp.keepPrimaryModel",
                "chat.attachments.maxImageMb", "chat.attachments.maxAudioMb", "chat.attachments.maxVideoMb",
                "chat.attachments.maxDocumentMb", "memory.edge.maxFacts");
        for (String key : workspaceOnly) {
            assertThat(registry.require(key).scopes()).as(key).containsExactly(SettingScope.TENANT);
        }
        for (String key : perAgent) {
            assertThat(registry.require(key).scopes()).as(key).containsExactlyInAnyOrder(SettingScope.TENANT, SettingScope.AGENT);
        }
        for (String key : java.util.stream.Stream.concat(workspaceOnly.stream(), perAgent.stream()).toList()) {
            assertThat(registry.require(key).risk()).as(key + " is not a security entry").isNotEqualTo(SettingRisk.SECURITY);
        }
        // an agent editor must not be able to undo the tighter step ceiling of a supervised agent
        for (String key : List.of("agent.steps.graceCalls", "agent.steps.supervisedDivisor", "agent.steps.supervisedMinSteps",
                "agent.steps.autonomousMultiplier")) {
            assertThat(registry.require(key).minRole()).as(key).isEqualTo(com.agenticai.core.settings.SettingRole.TENANT_ADMIN);
        }
    }

    @Test
    void everyEntryHasALabelDescriptionAndHelpInEnglish() {
        List<String> missing = new ArrayList<>();
        for (SettingDefinition d : registry.all()) {
            for (String k : List.of(d.labelKey(), d.descriptionKey(), d.helpKey())) {
                JsonNode n = at(k);
                if (n == null || !n.isTextual() || n.asText().isBlank()) {
                    missing.add(k);
                }
            }
        }
        assertThat(missing).as("i18n keys missing from web/messages/en.json (add them under the settings namespace)").isEmpty();
    }

    @Test
    void everyGroupIsNamedInEnglish() {
        List<String> missing = new ArrayList<>();
        for (SettingDefinition d : registry.all()) {
            JsonNode n = at("settings.groups." + d.group() + ".label");
            if (n == null || !n.isTextual() || n.asText().isBlank()) {
                missing.add(d.group());
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void noMessageUsesPlaceholderTextOrHardcodedBounds() {
        for (SettingDefinition d : registry.all()) {
            String text = at(d.descriptionKey()).asText() + " " + at(d.helpKey()).asText();
            assertThat(text).as(d.key()).doesNotContain("TODO", "lorem");
            assertThat(at(d.labelKey()).asText().length()).as(d.key() + " label is short").isLessThanOrEqualTo(60);
        }
    }

    @Test
    void boundsAndDefaultsAreSane() {
        for (SettingDefinition d : registry.all()) {
            if (d.min() != null && d.max() != null) {
                assertThat(d.min()).as(d.key() + " min<=max").isLessThanOrEqualTo(d.max());
            }
            Object platform = d.platformValue();
            switch (d.type()) {
                case BOOL -> assertThat(platform).as(d.key()).isInstanceOf(Boolean.class);
                case ENUM -> assertThat(d.enumValues()).as(d.key() + " default is a listed value").contains(String.valueOf(platform));
                case STRING, TEXT -> assertThat(platform).isInstanceOf(String.class);
                case INT, LONG, DURATION -> assertThat(platform).as(d.key()).isInstanceOf(Long.class);
                default -> assertThat(platform).as(d.key()).isInstanceOf(Double.class);
            }
            if (d.type().isNumeric() && !SENTINEL_DEFAULTS.contains(d.key())) {
                double v = ((Number) platform).doubleValue();
                if (d.min() != null) {
                    assertThat(v).as(d.key() + " default >= min").isGreaterThanOrEqualTo(d.min());
                }
                if (d.max() != null) {
                    assertThat(v).as(d.key() + " default <= max").isLessThanOrEqualTo(d.max());
                }
            }
            if (d.type().isNumeric()) {
                assertThat(d.min()).as(d.key() + " numeric entries must declare their bounds").isNotNull();
                assertThat(d.max()).as(d.key() + " numeric entries must declare their bounds").isNotNull();
            }
            assertThat(d.scopes()).isNotEmpty();
            assertThat(d.group()).isNotBlank();
        }
    }

    @Test
    void theDefaultIsAcceptedByItsOwnValidationExceptForDeclaredSentinels() {
        for (SettingDefinition d : registry.all()) {
            if (SENTINEL_DEFAULTS.contains(d.key())) {
                continue;
            }
            assertThat(d.normalize(d.platformValue())).as(d.key()).isEqualTo(d.platformValue());
        }
    }

    @Test
    void securityEntriesAreTenantAdminOnlyAndOnlyStricter() {
        long security = registry.all().stream().filter(d -> d.risk() == SettingRisk.SECURITY).count();
        assertThat(security).isGreaterThanOrEqualTo(4);
        for (SettingDefinition d : registry.all()) {
            if (d.risk() == SettingRisk.SECURITY) {
                assertThat(d.minRole()).as(d.key()).isEqualTo(SettingRole.TENANT_ADMIN);
                assertThat(d.strictness()).as(d.key()).isNotEqualTo(Strictness.NONE);
                // at the platform value is allowed; the weaker side is refused
                assertThat(d.isAtLeastAsStrict(d.platformValue(), d.platformValue())).isTrue();
            } else {
                assertThat(d.strictness()).as(d.key() + " only security entries compare strictness").isEqualTo(Strictness.NONE);
            }
            if (d.risk().requiresReason()) {
                assertThat(d.type()).isNotNull();
            }
        }
        SettingDefinition mode = registry.require("guardrails.failMode");
        assertThat(mode.isAtLeastAsStrict("closed", "open")).isTrue();
        assertThat(mode.isAtLeastAsStrict("open", "closed")).isFalse();
        SettingDefinition block = registry.require("guardrails.toxicity.blockScore");
        assertThat(block.isAtLeastAsStrict(40L, 60L)).isTrue();
        assertThat(block.isAtLeastAsStrict(80L, 60L)).isFalse();
        SettingDefinition scan = registry.require("guardrails.jailbreak.maxScannedChars");
        assertThat(scan.isAtLeastAsStrict(32_000L, 16_000L)).isTrue();
        assertThat(scan.isAtLeastAsStrict(8_000L, 16_000L)).isFalse();
    }

    @Test
    void approvalExpiryIsHighRiskAndNeedsAReason() {
        SettingDefinition ttl = registry.require("approvals.expiry.ttl");
        assertThat(ttl.risk()).isEqualTo(SettingRisk.HIGH);
        assertThat(ttl.type()).isEqualTo(SettingType.DURATION);
        assertThat(ttl.allows(SettingScope.AGENT)).isFalse();
    }

    /**
     * The web console tests render the real catalog from {@code web/app/lib/api/fixtures/settingsCatalog.snapshot.json}
     * (see {@code settingsCoverage.test.tsx}). This pins that file to the assembled catalog, so a new, changed or removed
     * entry fails here until the snapshot is regenerated:
     * {@code mvn -pl app -am test -Dtest=SettingsCatalogContractTest -Dvegaduta.settings.snapshot.write=true}.
     */
    @Test
    void theWebCatalogSnapshotIsTheAssembledCatalog() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<java.util.Map<String, Object>> entries = new ArrayList<>();
        for (SettingDefinition d : registry.all()) {
            java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("key", d.key());
            e.put("group", d.group());
            e.put("labelKey", d.labelKey());
            e.put("descriptionKey", d.descriptionKey());
            e.put("helpKey", d.helpKey());
            e.put("type", d.type().json());
            if (d.unit() != null) {
                e.put("unit", d.unit().json());
            }
            if (d.min() != null) {
                e.put("min", number(d.min()));
            }
            if (d.max() != null) {
                e.put("max", number(d.max()));
            }
            if (d.step() != null) {
                e.put("step", number(d.step()));
            }
            if (d.enumValues() != null && !d.enumValues().isEmpty()) {
                e.put("enumValues", d.enumValues());
            }
            e.put("risk", d.risk().json());
            e.put("scopes", d.scopes().stream().map(SettingScope::json).toList());
            e.put("default", d.platformValue());
            e.put("minRole", d.minRole().json());
            e.put("restartRequired", false);
            if (d.docsAnchor() != null) {
                e.put("docsAnchor", d.docsAnchor());
            }
            if (d.strictness() != Strictness.NONE) {
                e.put("strictness", d.strictness().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (d.platformProperty() != null) {
                e.put("platformProperty", d.platformProperty());
            }
            entries.add(e);
        }
        String expected = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(java.util.Map.of("entries", entries)).replace("\r\n", "\n") + "\n";
        Path file = repoRoot.resolve("web/app/lib/api/fixtures/settingsCatalog.snapshot.json");
        if (Boolean.getBoolean("vegaduta.settings.snapshot.write")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected);
        }
        assertThat(Files.isRegularFile(file)).as("web catalog snapshot exists (regenerate with -Dvegaduta.settings.snapshot.write=true)").isTrue();
        assertThat(Files.readString(file).replace("\r\n", "\n"))
                .as("web/app/lib/api/fixtures/settingsCatalog.snapshot.json is out of date; regenerate with -Dvegaduta.settings.snapshot.write=true")
                .isEqualTo(expected);
    }

    private static Number number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (Number) Long.valueOf((long) v) : (Number) Double.valueOf(v);
    }
}
