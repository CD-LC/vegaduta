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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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
                        eq(newId),
                        eq(new LinkedHashSet<>(List.of("fetch_web_page", "request_human"))));
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
        assertThat(exported.agent().path("systemPrompt").asString())
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
                assertThat(preview.autonomyTier())
                        .as(f.getFileName().toString())
                        .isEqualTo("SUPERVISED");
                assertThat(preview.adjustments()).as(f.getFileName().toString()).isEmpty();
            }
        }
    }
}
