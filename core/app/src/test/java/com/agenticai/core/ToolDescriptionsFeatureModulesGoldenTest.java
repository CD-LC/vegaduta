package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.ToolDescriptionResolver;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptRiskClass;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.tool.ToolPromptGolden;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Golden test (package 4e-2) for the built-in tools of the feature modules - codeintel, elevenlabs,
 * google, graphmemory, imagegen, knowledge, payments, sarvam, workflow - whose description and
 * parameter texts live in the prompt registry: for every tool, the text its code builds is the
 * shipped default of its keys, byte for byte, so an unconfigured platform sends the model exactly the
 * tool list it sent before the migration. It lives in the app module because it needs all of these
 * modules on one classpath, the way the application has them.
 *
 * <p>Tools served by the platform's own MCP server (core/mcpserver) and the pseudo tools of the
 * provider shims are not in this list on purpose: see docs/prompts.md, "Tool descriptions".
 */
class ToolDescriptionsFeatureModulesGoldenTest {

    /** A tool class, the registry id it is registered under, and the fields its definition reads. */
    record Tool(String id, String type, Map<String, Object> fields) {
        Tool(String id, String type) {
            this(id, type, Map.of());
        }
    }

    static final List<Tool> TOOLS =
        List.of(
            new Tool("apply_code_changes", "com.agenticai.core.codeintel.ApplyCodeChangesToolCallback"),
            new Tool("code_callees", "com.agenticai.core.codeintel.CodeCalleesToolCallback"),
            new Tool("code_callers", "com.agenticai.core.codeintel.CodeCallersToolCallback"),
            new Tool("code_graph_stats", "com.agenticai.core.codeintel.CodeGraphStatsToolCallback"),
            new Tool("code_impact", "com.agenticai.core.codeintel.CodeImpactToolCallback"),
            new Tool("create_code_workspace", "com.agenticai.core.codeintel.CreateCodeWorkspaceToolCallback"),
            new Tool("delete_code_workspace", "com.agenticai.core.codeintel.DeleteCodeWorkspaceToolCallback"),
            new Tool("diff_code_workspace", "com.agenticai.core.codeintel.DiffCodeWorkspaceToolCallback"),
            new Tool("run_in_code_workspace", "com.agenticai.core.codeintel.RunInCodeWorkspaceToolCallback"),
            new Tool("search_code", "com.agenticai.core.codeintel.SearchCodeToolCallback"),
            new Tool("generate_music", "com.agenticai.core.elevenlabs.agenttools.GenerateMusicToolCallback"),
            new Tool("google_calendar_cancel_event", "com.agenticai.core.google.agenttools.GoogleCalendarChangeEventToolCallback", Map.of("cancelling", true)),
            new Tool("google_calendar_move_event", "com.agenticai.core.google.agenttools.GoogleCalendarChangeEventToolCallback", Map.of("cancelling", false)),
            new Tool("google_calendar_find_free_slots", "com.agenticai.core.google.agenttools.GoogleCalendarFreeSlotsToolCallback"),
            new Tool("google_calendar", "com.agenticai.core.google.agenttools.GoogleCalendarToolCallback"),
            new Tool("google_drive", "com.agenticai.core.google.agenttools.GoogleDriveToolCallback"),
            new Tool("google_gmail", "com.agenticai.core.google.agenttools.GoogleGmailToolCallback"),
            new Tool("google_sheets", "com.agenticai.core.google.agenttools.GoogleSheetsToolCallback"),
            new Tool("youtube_content_performance", "com.agenticai.core.google.agenttools.GoogleYouTubeToolCallback"),
            new Tool("upload_video_to_youtube", "com.agenticai.core.google.agenttools.UploadVideoToYouTubeToolCallback"),
            new Tool("recall_related_facts", "com.agenticai.core.graphmemory.GraphRecallToolCallback"),
            new Tool("recall_shared_graph_facts", "com.agenticai.core.graphmemory.RecallSharedGraphFactsToolCallback"),
            new Tool("generate_image", "com.agenticai.core.imagegen.agenttools.GenerateImageToolCallback"),
            new Tool("generate_video", "com.agenticai.core.imagegen.agenttools.GenerateVideoToolCallback"),
            new Tool("add_to_knowledge_base", "com.agenticai.core.knowledge.AddToKnowledgeBaseToolCallback"),
            new Tool("search_knowledge_base", "com.agenticai.core.knowledge.KnowledgeSearchToolCallback"),
            new Tool("list_knowledge_collections", "com.agenticai.core.knowledge.ListKnowledgeCollectionsToolCallback"),
            new Tool("check_payment_status.mandate", "com.agenticai.core.payments.agenttools.MandateCheckPaymentStatusToolCallback"),
            new Tool("create_payment_link.mandate", "com.agenticai.core.payments.agenttools.MandateCreatePaymentLinkToolCallback"),
            new Tool("request_upi_collect", "com.agenticai.core.payments.agenttools.MandateRequestUpiCollectToolCallback"),
            new Tool("sarvam_identify_language", "com.agenticai.core.sarvam.agenttools.SarvamIdentifyLanguageToolCallback"),
            new Tool("sarvam_transcribe", "com.agenticai.core.sarvam.agenttools.SarvamTranscribeToolCallback"),
            new Tool("sarvam_translate", "com.agenticai.core.sarvam.agenttools.SarvamTranslateToolCallback"),
            new Tool("sarvam_transliterate", "com.agenticai.core.sarvam.agenttools.SarvamTransliterateToolCallback"),
            new Tool("create_workflow", "com.agenticai.core.workflow.CreateWorkflowToolCallback"));

    /**
     * The tools that act on the outside world, move money, run code or carry an approval notice in
     * their wording: changing one of these descriptions needs {@code acknowledgeRisk}, because a
     * weaker text can make the model skip an approval.
     */
    private static final Set<String> SECURITY_CRITICAL =
        Set.of(
            "apply_code_changes",
            "run_in_code_workspace",
            "delete_code_workspace",
            "create_payment_link.mandate",
            "request_upi_collect",
            "google_calendar",
            "google_calendar_cancel_event",
            "google_calendar_move_event",
            "google_gmail",
            "google_sheets",
            "google_drive",
            "upload_video_to_youtube");

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-b", "admin@b", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void everyToolsRegistryDefaultsEqualWhatItsCodeBuilds() throws Exception {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        List<String> problems = new ArrayList<>();
        for (Tool tool : TOOLS) {
            ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(tool.type()), tool.fields());
            problems.addAll(ToolPromptGolden.problems(catalog, tool.id(), probed.description(), probed.inputSchema()));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theToolNameOfEveryProbeMatchesItsRegistryId() throws Exception {
        for (Tool tool : TOOLS) {
            ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(tool.type()), tool.fields());
            assertThat(probed.name()).as(tool.type()).isEqualTo(tool.id().replaceFirst("\\.[a-z0-9_-]+$", ""));
        }
    }

    @Test
    void everyToolKeyIsADeclaredToolDescriptionWithoutPlaceholders() throws Exception {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        for (Tool tool : TOOLS) {
            var def = catalog.find("tool.description." + tool.id()).orElseThrow();
            assertThat(def.riskClass()).as(def.key()).isEqualTo(PromptRiskClass.TOOL_DESCRIPTION);
            assertThat(def.disableable()).as(def.key() + " disableable").isFalse();
            assertThat(def.securityCritical()).as(def.key() + " securityCritical").isEqualTo(SECURITY_CRITICAL.contains(tool.id()));
            assertThat(def.placeholders()).as(def.key() + " placeholders").isEmpty();
            assertThat(def.title()).isNotBlank();
            assertThat(def.description()).isNotBlank();
        }
    }

    @Test
    void theCatalogOfTheApplicationHasNoProblemWithTheseKeys() {
        List<String> mine = TOOLS.stream().map(Tool::id).toList();
        assertThat(PromptResolver.defaultsOnly().catalog().problems())
            .noneMatch(p -> mine.stream().anyMatch(id -> p.contains("tool.description." + id) || p.contains("tool.param." + id + ".")));
    }

    /** The two payment tools share their names with the commerce tools of core/agents-tools; they are the .mandate variants. */
    @Test
    void thePaymentToolsThatShareANameWithAnotherToolAreVariants() {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        for (String name : List.of("check_payment_status", "create_payment_link")) {
            assertThat(catalog.find("tool.description." + name)).as(name + " (commerce)").isPresent();
            assertThat(catalog.find("tool.description." + name + ".mandate")).as(name + " (mandate)").isPresent();
            assertThat(catalog.defaultText("tool.description." + name, catalog.defaultLocale()))
                .isNotEqualTo(catalog.defaultText("tool.description." + name + ".mandate", catalog.defaultLocale()));
        }
    }

    /** The decorator picks the variant whose shipped text equals what the code built, and an override reaches only that one. */
    @Test
    void anOverrideOfAMandateToolReachesOnlyTheMandateImplementation() throws Exception {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
        DefaultPromptResolver resolver = new DefaultPromptResolver(catalog, store, new PromptProperties());
        ToolDescriptionResolver decorator = ToolDescriptionResolver.of(resolver);
        Tool mandate = TOOLS.stream().filter(t -> t.id().equals("check_payment_status.mandate")).findFirst().orElseThrow();
        ToolPromptGolden.Probed probed = ToolPromptGolden.probe(Class.forName(mandate.type()), mandate.fields());
        ToolCallback stub = new Stub(probed);

        assertThat(decorator.apply(stub, AGENT)).as("no override: the same object").isSameAs(stub);

        // an override of the commerce tool of the same name does not touch the mandate tool
        put(store, PromptScope.TENANT, "", "tool.description.check_payment_status", "Commerce wording.");
        resolver.evictAll();
        assertThat(decorator.apply(stub, AGENT)).isSameAs(stub);

        put(store, PromptScope.AGENT, AGENT.toString(), "tool.description.check_payment_status.mandate", "Mandate wording for one agent.");
        resolver.evictAll();
        assertThat(decorator.apply(stub, AGENT).getToolDefinition().description()).isEqualTo("Mandate wording for one agent.");
        assertThat(decorator.apply(stub, AGENT).getToolDefinition().name()).isEqualTo("check_payment_status");
        assertThat(decorator.apply(stub, AGENT).getToolDefinition().inputSchema()).isEqualTo(probed.inputSchema());
        // another agent of the same tenant keeps the shipped wording
        assertThat(decorator.apply(stub, UUID.fromString("00000000-0000-0000-0000-0000000000c3"))).isSameAs(stub);
    }

    private static void put(InMemoryPromptOverrideStore store, PromptScope scope, String scopeId, String key, String text) {
        store.put(new PromptOverride(scope, scopeId, key, "en", text, true, 1, "tester", Instant.now()));
    }

    private record Stub(ToolPromptGolden.Probed probed) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name(probed.name())
                .description(probed.description())
                .inputSchema(probed.inputSchema())
                .build();
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return "ok";
        }
    }
}
