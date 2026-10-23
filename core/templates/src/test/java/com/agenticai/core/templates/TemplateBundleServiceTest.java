package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.templates.dto.AgentBundle;
import com.agenticai.core.templates.dto.AgentBundlePreview;
import com.agenticai.core.templates.dto.TemplateImportResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shareable agent files: a bundle is untrusted, so import lowers autonomy and rejects junk. */
class TemplateBundleServiceTest extends CommunityTemplateServiceTestBase {

    private TemplateBundleService bundles;

    @BeforeEach
    void setUp() {
        bundles =
                new TemplateBundleService(
                        snapshotService,
                        readinessService,
                        agentService,
                        service,
                        agentGuardrailService,
                        workflowService,
                        importRateLimiter,
                        auditLogService,
                        mapper);
        asMember("importer-user");
    }

    private ObjectNode design(String tier) {
        ObjectNode agent = mapper.createObjectNode();
        agent.put("systemPrompt", "You are a helper.");
        agent.put("providerName", "PLATFORM_HOSTED");
        agent.put("maxTokens", 1024);
        agent.put("temperature", 0.2);
        agent.put("topP", 0.9);
        agent.put("autonomyTier", tier);
        agent.putArray("allowedToolNames").add("fetch_web_page").add("request_human");
        agent.putArray("allowedKnowledgeCollectionIds").add(UUID.randomUUID().toString());
        return agent;
    }

    private AgentBundle bundle(String format, JsonNode agent) {
        return new AgentBundle(format, "Helper", "Helps.", "Productivity", List.of(), agent);
    }

    @Test
    void import_createsTheAgentWithItsToolsAndSupervisedTier() {
        UUID newId = UUID.randomUUID();
        when(agentService.create(any())).thenReturn(AgentResponse.from(agentWithId(newId)));

        TemplateImportResponse response =
                bundles.importBundle(bundle(TemplateBundleService.FORMAT, design("SUPERVISED")));

        assertThat(response.resourceId()).isEqualTo(newId);
        verify(agentService).setAutonomyTier(newId, AgentAutonomyTier.SUPERVISED);
        verify(agentService)
                .setAllowedToolNames(
                        eq(newId), eq(new LinkedHashSet<>(List.of("fetch_web_page", "request_human"))));
        verify(auditLogService)
                .record(any(), eq("agent.bundle.import"), eq("agent"), any(), any(), any());
    }

    @Test
    void import_neverGrantsFullAutonomyFromAFile() {
        UUID newId = UUID.randomUUID();
        when(agentService.create(any())).thenReturn(AgentResponse.from(agentWithId(newId)));

        bundles.importBundle(bundle(TemplateBundleService.FORMAT, design("FULLY_AUTONOMOUS")));

        verify(agentService).setAutonomyTier(newId, AgentAutonomyTier.SUPERVISED);
        verify(agentService, never()).setAutonomyTier(newId, AgentAutonomyTier.FULLY_AUTONOMOUS);
    }

    @Test
    void preview_saysWhatWasAdjustedAndCreatesNothing() {
        AgentBundlePreview preview =
                bundles.preview(bundle(TemplateBundleService.FORMAT, design("FULLY_AUTONOMOUS")));

        assertThat(preview.autonomyTier()).isEqualTo("SUPERVISED");
        assertThat(preview.tools()).containsExactly("fetch_web_page", "request_human");
        assertThat(preview.adjustments())
                .anyMatch(a -> a.contains("FULLY_AUTONOMOUS"))
                .anyMatch(a -> a.contains("knowledge-base"));
        verify(agentService, never()).create(any());
    }

    @Test
    void import_dropsKnowledgeBaseIdsFromTheAuthor() {
        when(agentService.create(any()))
                .thenReturn(AgentResponse.from(agentWithId(UUID.randomUUID())));
        AgentBundle b = bundle(TemplateBundleService.FORMAT, design("SUPERVISED"));

        AgentBundlePreview preview = bundles.preview(b);

        assertThat(preview.adjustments()).anyMatch(a -> a.contains("knowledge-base"));
    }

    @Test
    void import_rejectsAnUnknownFormatAndAMissingPrompt() {
        assertThatThrownBy(() -> bundles.importBundle(bundle("other/9", design("SUPERVISED"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported bundle format");
        ObjectNode noPrompt = design("SUPERVISED");
        noPrompt.remove("systemPrompt");
        assertThatThrownBy(
                        () -> bundles.importBundle(bundle(TemplateBundleService.FORMAT, noPrompt)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system prompt");
        verify(agentService, never()).create(any());
    }

    /**
     * The original defect: the shipped examples said {@code "reasoningDepth": "BALANCED"}, which is
     * not a {@code ReasoningDepth} value, and the server silently turned it into FAST. An unknown
     * enum value must now be rejected, naming the field, the bad value and the allowed values.
     */
    @Test
    void import_rejectsAnUnknownReasoningDepthInsteadOfSilentlyDefaulting() {
        ObjectNode agent = design("SUPERVISED");
        agent.put("reasoningDepth", "BALANCED");

        assertThatThrownBy(() -> bundles.importBundle(bundle(TemplateBundleService.FORMAT, agent)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoningDepth")
                .hasMessageContaining("'BALANCED'")
                .hasMessageContaining("FAST, CAREFUL, RIGOROUS, PLANNED");
        assertThatThrownBy(() -> bundles.preview(bundle(TemplateBundleService.FORMAT, agent)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoningDepth");
        verify(agentService, never()).create(any());
        verify(agentService, never()).setAutonomyTier(any(), any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "reasoningDepth",
                "autonomyTier",
                "tuningMode",
                "experienceMode",
                "providerName",
                "economyProviderName",
                "fallbackProviderName",
                "voiceModelProvider",
                "videoModelProvider"
            })
    void import_rejectsAnUnknownValueForEveryEnumField(String field) {
        ObjectNode agent = design("SUPERVISED");
        agent.put(field, "NOT_A_REAL_VALUE");

        assertThatThrownBy(() -> bundles.importBundle(bundle(TemplateBundleService.FORMAT, agent)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field)
                .hasMessageContaining("'NOT_A_REAL_VALUE'")
                .hasMessageContaining("allowed:");
        verify(agentService, never()).create(any());
    }

    @Test
    void import_reportsEveryBadEnumAtOnce() {
        ObjectNode agent = design("SUPERVISED");
        agent.put("reasoningDepth", "BALANCED");
        agent.put("tuningMode", "AUTO");

        assertThatThrownBy(() -> bundles.preview(bundle(TemplateBundleService.FORMAT, agent)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoningDepth")
                .hasMessageContaining("tuningMode");
    }

    @Test
    void import_acceptsEveryRealValueOfTheEnumFieldsAndAbsentOnes() {
        for (var depth : com.agenticai.core.agents.core.ReasoningDepth.values()) {
            ObjectNode agent = design("SUPERVISED");
            agent.put("reasoningDepth", depth.name());
            agent.put("tuningMode", "SIMPLE");
            agent.putNull("experienceMode");
            assertThat(bundles.preview(bundle(TemplateBundleService.FORMAT, agent)).autonomyTier())
                    .isEqualTo("SUPERVISED");
        }
        // No enum fields at all (design() sets none of them) stays valid: absent means "use default".
        assertThat(bundles.preview(bundle(TemplateBundleService.FORMAT, design("SUPERVISED"))))
                .isNotNull();
    }

    @Test
    void import_rejectsAnOversizedDesign() {
        ObjectNode big = design("SUPERVISED");
        big.put("systemPrompt", "x".repeat(TemplateBundleService.MAX_AGENT_JSON_CHARS + 1));
        assertThatThrownBy(() -> bundles.importBundle(bundle(TemplateBundleService.FORMAT, big)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    void export_thenImport_roundTripsTheDesign() {
        UUID sourceId = UUID.randomUUID();
        Agent source = agentWithId(sourceId);
        source.setAutonomyTier(AgentAutonomyTier.SUPERVISED);
        when(agentService.get(sourceId)).thenReturn(AgentResponse.from(source));
        when(agentRepository.findById(sourceId)).thenReturn(Optional.of(source));

        AgentBundle exported = bundles.export(sourceId);

        assertThat(exported.format()).isEqualTo(TemplateBundleService.FORMAT);
        assertThat(exported.agent().path("systemPrompt").asText())
                .isEqualTo("You are a returns assistant.");
        assertThat(exported.agent().path("allowedKnowledgeCollectionIds")).isEmpty();
    }

    /** Every shipped example must be importable by the real server code, tools and tier intact. */
    @Test
    void everyShippedExampleBundle_previewsCleanly() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("..", "..", "examples", "agent-bundles");
        try (var files = java.nio.file.Files.list(dir)) {
            List<java.nio.file.Path> bundleFiles =
                    files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
            assertThat(bundleFiles).hasSizeGreaterThanOrEqualTo(4);
            for (java.nio.file.Path f : bundleFiles) {
                AgentBundle b = mapper.readValue(f.toFile(), AgentBundle.class);
                AgentBundlePreview preview = bundles.preview(b);
                assertThat(preview.tools()).as(f.getFileName().toString()).isNotEmpty();
                assertThat(preview.autonomyTier()).as(f.getFileName().toString()).isEqualTo("SUPERVISED");
                assertThat(preview.adjustments()).as(f.getFileName().toString()).isEmpty();
                // Every enum-typed value in a shipped file is a real enum constant (preview would
                // have thrown otherwise); pin the depth so a typo like BALANCED cannot come back.
                assertThat(b.agent().path("reasoningDepth").asText())
                        .as(f.getFileName().toString())
                        .isIn("FAST", "CAREFUL", "RIGOROUS", "PLANNED");
            }
        }
    }

    /** The offline checker's enum table must list exactly the constants the server accepts. */
    @Test
    void validatePy_listsExactlyTheEnumConstantsTheServerAccepts() throws Exception {
        String py =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("..", "..", "examples", "agent-bundles", "validate.py"));
        String block = py.substring(py.indexOf("TIERS = {"), py.indexOf("ENUM_FIELDS[\"economyProviderName\"]"));
        java.util.Set<String> inPy = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Z][A-Z0-9_]+)\"").matcher(block);
        while (m.find()) {
            inPy.add(m.group(1));
        }
        java.util.Set<String> inJava = new java.util.TreeSet<>();
        for (Class<? extends Enum<?>> type : TemplateSnapshotService.AGENT_ENUM_FIELDS.values()) {
            for (Enum<?> c : type.getEnumConstants()) {
                inJava.add(c.name());
            }
        }
        assertThat(inPy).isEqualTo(inJava);
        assertThat(py).contains("\"voiceModelProvider\"", "\"videoModelProvider\"", "\"reasoningDepth\"");
    }

    /** The console serves copies of the examples; they must validate too and stay identical. */
    @Test
    void theConsoleCopiesOfTheExamplesAreIdenticalAndValid() throws Exception {
        java.nio.file.Path examples = java.nio.file.Path.of("..", "..", "examples", "agent-bundles");
        java.nio.file.Path web = java.nio.file.Path.of("..", "..", "web", "public", "agent-bundles");
        try (var files = java.nio.file.Files.list(examples)) {
            for (java.nio.file.Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                java.nio.file.Path copy = web.resolve(f.getFileName());
                assertThat(java.nio.file.Files.exists(copy)).as(copy.toString()).isTrue();
                assertThat(mapper.readTree(copy.toFile()))
                        .as(f.getFileName().toString())
                        .isEqualTo(mapper.readTree(f.toFile()));
                bundles.preview(mapper.readValue(copy.toFile(), AgentBundle.class));
            }
        }
    }
}
