package com.agenticai.core.agents.importer;

import static com.agenticai.core.agents.importer.AgentImportFixtures.MAPPER;
import static com.agenticai.core.agents.importer.AgentImportFixtures.hasConverted;
import static com.agenticai.core.agents.importer.AgentImportFixtures.hasNeedsManual;
import static com.agenticai.core.agents.importer.AgentImportFixtures.json;
import static com.agenticai.core.agents.importer.AgentImportFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.importer.AgentImportDtos.ImportResponse;
import com.agenticai.core.agents.importer.AgentImportDtos.PreviewResponse;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentImportServiceTest {

    private AgentService agentService;
    private ProviderModelCatalogService catalog;
    private TenantLimits tenantLimits;
    private AgentImportService service;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        catalog = mock(ProviderModelCatalogService.class);
        tenantLimits = mock(TenantLimits.class);
        service = new AgentImportService(agentService, catalog, tenantLimits, true, 1_048_576L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static String envelope(String source, JsonNode payload) {
        ObjectNode env = MAPPER.createObjectNode();
        if (source != null) {
            env.put("source", source);
        }
        env.set("payload", payload);
        return env.toString();
    }

    private static ProviderModelResponse model(String id) {
        return new ProviderModelResponse(
                id, id, 128_000, false, false, false, null, null, false, "SEED");
    }

    // ---- preview
    // ---------------------------------------------------------------------------------------

    @Test
    void previewTouchesNothingAndShapesTheContractExactly() {
        when(catalog.findModel(ProviderName.OPENAI, "gpt-4o"))
                .thenReturn(Optional.of(model("gpt-4o")));

        PreviewResponse res = service.preview(envelope("auto", json("openai-assistant.json")));

        verifyNoInteractions(agentService);
        assertThat(res.source()).isEqualTo("auto");
        assertThat(res.detectedSource()).isEqualTo("openai-assistant");
        assertThat(res.agent().name()).isEqualTo("Math Tutor");
        assertThat(res.agent().description())
                .isEqualTo("Personal math tutor that writes and runs code.");
        assertThat(res.agent().instructions()).startsWith("You are a personal math tutor.");
        assertThat(res.agent().modelProvider()).isEqualTo("OPENAI");
        assertThat(res.agent().model()).isEqualTo("gpt-4o");
        assertThat(res.agent().temperature()).isEqualTo(1.0);
        assertThat(res.agent().suggestedCapabilities()).containsExactly("e2b-code-execution");
        assertThat(res.agent().suggestedTools()).contains("run_code", "search_knowledge_base");
        assertThat(hasConverted(res.report(), "Model")).isTrue();
        assertThat(hasNeedsManual(res.report(), "Function tool 'get_order_status'")).isTrue();
    }

    @Test
    void previewLeavesTheModelBlankWhenTheCatalogDoesNotOfferIt() {
        when(catalog.findModel(eq(ProviderName.OPENAI), any())).thenReturn(Optional.empty());

        PreviewResponse res =
                service.preview(envelope("openai-assistant", json("openai-assistant.json")));

        assertThat(res.agent().modelProvider()).isEqualTo("OPENAI");
        assertThat(res.agent().model()).isEmpty();
        assertThat(hasNeedsManual(res.report(), "Model 'gpt-4o'")).isTrue();
    }

    @Test
    void previewSurvivesACatalogFailure() {
        when(catalog.findModel(any(), any())).thenThrow(new IllegalStateException("catalog down"));

        PreviewResponse res = service.preview(envelope("auto", json("openai-assistant.json")));

        assertThat(res.agent().model()).isEmpty();
        assertThat(res.report().warnings())
                .anyMatch(w -> w.contains("catalog could not be checked"));
    }

    @Test
    void unknownProviderFallsBackToNoProviderRatherThanAGuess() {
        // a Flowise chat model type the importer does not know
        String flow =
                "{\"nodes\":[{\"id\":\"m\",\"data\":{\"id\":\"m\",\"name\":\"chatWeirdLLM\",\"label\":\"Weird\",\"category\":\"Chat Models\","
                        + "\"inputs\":{\"modelName\":\"weird-1\"}}},{\"id\":\"a\",\"data\":{\"id\":\"a\",\"name\":\"toolAgent\",\"category\":\"Agents\","
                        + "\"inputs\":{\"systemMessage\":\"hi\",\"model\":\"{{m.data.instance}}\"}}}],\"edges\":[]}";

        PreviewResponse res = service.preview(envelope("flowise", parseNode(flow)));

        assertThat(res.agent().modelProvider()).isEmpty();
        assertThat(res.agent().model()).isEmpty();
        assertThat(hasNeedsManual(res.report(), "Model")).isTrue();
        verifyNoInteractions(catalog);
    }

    @Test
    void flowiseStoredRecordWithFlowDataStringIsUnwrapped() {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("id", "abc");
        record.put("name", "Stored flow");
        record.put("flowData", text("flowise-tool-agent.json"));

        PreviewResponse res = service.preview(envelope("auto", record));

        assertThat(res.detectedSource()).isEqualTo("flowise");
        assertThat(res.agent().name()).isEqualTo("Stored flow");
        assertThat(res.agent().instructions()).startsWith("You are a helpful research assistant.");
    }

    @Test
    void payloadMayBeAJsonString() {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("source", "openai-agents");
        env.put("payload", text("openai-agents-sdk.json"));

        PreviewResponse res = service.preview(env.toString());

        assertThat(res.detectedSource()).isEqualTo("openai-agents");
        assertThat(res.agent().name()).isEqualTo("Triage agent");
    }

    // ---- import
    // ----------------------------------------------------------------------------------------

    @Test
    void importCreatesADraftThroughTheNormalCreatePath() {
        when(catalog.findModel(ProviderName.OPENAI, "gpt-4o"))
                .thenReturn(Optional.of(model("gpt-4o")));
        UUID id = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(id);
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(created);

        ImportResponse res = service.importAgent(envelope("auto", json("openai-assistant.json")));

        ArgumentCaptor<AgentCreateRequest> captor =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(captor.capture());
        AgentCreateRequest sent = captor.getValue();
        assertThat(sent.name()).isEqualTo("Math Tutor");
        assertThat(sent.systemPrompt()).startsWith("You are a personal math tutor.");
        assertThat(sent.providerName()).isEqualTo(ProviderName.OPENAI);
        assertThat(sent.model()).isEqualTo("gpt-4o");
        assertThat(sent.temperature()).isEqualTo(1.0);
        // nothing is switched on by the import itself
        assertThat(sent.knowledgeBaseEnabled()).isNull();
        assertThat(sent.voiceModelProvider()).isNull();
        assertThat(sent.videoModelProvider()).isNull();
        assertThat(sent.ttsProvider()).isNull();

        assertThat(res.agentId()).isEqualTo(id);
        assertThat(hasConverted(res.report(), "Draft agent")).isTrue();
    }

    @Test
    void importPinsAdvancedModeAndNarrowsTheToolsToTheReadOnlySet() {
        UUID id = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(id);
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(created);

        ImportResponse res = service.importAgent(envelope("auto", json("openai-assistant.json")));

        ArgumentCaptor<AgentCreateRequest> captor =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(captor.capture());
        assertThat(captor.getValue().experienceMode()).isEqualTo(ExperienceMode.ADVANCED);
        verify(agentService)
                .setAllowedToolNames(
                        id, Set.of("search_knowledge_base", "list_knowledge_collections"));
        assertThat(res.report().converted())
                .anyMatch(
                        c ->
                                c.detail().contains("private agent")
                                        && c.detail().contains("limited to knowledge-base"));
    }

    @Test
    void importStillSucceedsAndSaysSoWhenTheToolRestrictionFails() {
        UUID id = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(id);
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(created);
        when(agentService.setAllowedToolNames(any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        ImportResponse res = service.importAgent(envelope("auto", json("openai-assistant.json")));

        assertThat(res.agentId()).isEqualTo(id);
        assertThat(res.report().warnings()).anyMatch(w -> w.contains("could not be restricted"));
        assertThat(res.report().converted())
                .anyMatch(c -> c.detail().contains("could not be narrowed"));
    }

    @Test
    void freeTenantIsToldTheyGetThePlatformPoolNotTheSourceModel() {
        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));
        when(tenantLimits.plan("t1")).thenReturn(TenantPlan.FREE);

        PreviewResponse res = service.preview(envelope("auto", json("openai-assistant.json")));

        assertThat(res.agent().modelProvider()).isEmpty();
        assertThat(res.agent().model()).isEmpty();
        assertThat(res.report().warnings()).anyMatch(w -> w.contains("free plan"));
        verifyNoInteractions(catalog);
    }

    @Test
    void freeTenantImportSendsThePlatformPoolProvider() {
        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));
        when(tenantLimits.plan("t1")).thenReturn(TenantPlan.FREE);
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(UUID.randomUUID());
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(created);

        service.importAgent(envelope("auto", json("openai-assistant.json")));

        ArgumentCaptor<AgentCreateRequest> captor =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(captor.capture());
        assertThat(captor.getValue().providerName()).isEqualTo(ProviderName.PLATFORM_HOSTED);
        assertThat(captor.getValue().model()).isNull();
    }

    @Test
    void byokTenantIsToldTheyNeedTheirOwnProviderKey() {
        TenantContext.set(TenantContext.of("t2", "bob", Set.of()));
        when(tenantLimits.plan("t2")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.usesPlatformPool("t2")).thenReturn(false);
        when(catalog.findModel(ProviderName.OPENAI, "gpt-4o"))
                .thenReturn(Optional.of(model("gpt-4o")));

        PreviewResponse res = service.preview(envelope("auto", json("openai-assistant.json")));

        assertThat(res.agent().modelProvider()).isEqualTo("OPENAI");
        assertThat(hasNeedsManual(res.report(), "Provider key")).isTrue();
    }

    @Test
    void platformPoolTenantOnAPaidPlanIsNotAskedForAKey() {
        TenantContext.set(TenantContext.of("t3", "cy", Set.of()));
        when(tenantLimits.plan("t3")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.usesPlatformPool("t3")).thenReturn(true);
        when(catalog.findModel(ProviderName.OPENAI, "gpt-4o"))
                .thenReturn(Optional.of(model("gpt-4o")));

        PreviewResponse res = service.preview(envelope("auto", json("openai-assistant.json")));

        assertThat(hasNeedsManual(res.report(), "Provider key")).isFalse();
    }

    @Test
    void readBoundedStopsAtTheCapWith413() throws Exception {
        AgentImportService small =
                new AgentImportService(agentService, catalog, tenantLimits, true, 1024L);
        byte[] ok = new byte[1024 + AgentImportService.ENVELOPE_SLACK_BYTES];
        byte[] tooBig = new byte[ok.length + 1];

        assertThat(small.readBounded(new ByteArrayInputStream(ok))).hasSize(ok.length);
        assertThatThrownBy(() -> small.readBounded(new ByteArrayInputStream(tooBig)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void importHonoursTheNameOverrideAndSanitisesIt() {
        UUID id = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(id);
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(created);
        ObjectNode env = (ObjectNode) parseNode(envelope("auto", json("openai-assistant.json")));
        env.putObject("overrides").put("name", "  <script>Renamed</script>  ");

        service.importAgent(env.toString());

        ArgumentCaptor<AgentCreateRequest> captor =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(captor.capture());
        assertThat(captor.getValue().name())
                .doesNotContain("<")
                .doesNotContain(">")
                .contains("Renamed");
    }

    @Test
    void importWithUnmatchedProviderUsesThePlatformPoolAndNoModel() {
        UUID id = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(id);
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(created);

        service.importAgent(envelope("flowise", parseNode("{\"nodes\":[],\"edges\":[]}")));

        ArgumentCaptor<AgentCreateRequest> captor =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(captor.capture());
        assertThat(captor.getValue().providerName()).isEqualTo(ProviderName.PLATFORM_HOSTED);
        assertThat(captor.getValue().model()).isNull();
        assertThat(captor.getValue().systemPrompt())
                .isEqualTo(AgentImportFinalizer.PLACEHOLDER_INSTRUCTIONS);
    }

    @Test
    void planLimitFromTheNormalCreatePathPropagatesUnchanged() {
        when(agentService.create(any(AgentCreateRequest.class)))
                .thenThrow(new TenantLimitExceededException("agent cap reached"));

        assertThatThrownBy(
                        () -> service.importAgent(envelope("auto", json("openai-assistant.json"))))
                .isInstanceOf(TenantLimitExceededException.class);
    }

    // ---- flag, limits, hostile input
    // ---------------------------------------------------------------------

    @Test
    void disabledAnswers404ForBothRoutesAndDoesNothing() {
        AgentImportService off =
                new AgentImportService(agentService, catalog, tenantLimits, false, 1_048_576L);
        String body = envelope("auto", json("openai-assistant.json"));

        for (Runnable call :
                new Runnable[] {() -> off.preview(body), () -> off.importAgent(body)}) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verifyNoInteractions(agentService, catalog);
    }

    @Test
    void oversizedBodyIs413BeforeParsing() {
        AgentImportService small =
                new AgentImportService(agentService, catalog, tenantLimits, true, 1024L);
        String body =
                "{\"source\":\"auto\",\"payload\":{\"instructions\":\""
                        + "x".repeat(20_000)
                        + "\"}}";

        assertThatThrownBy(() -> small.preview(body))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        verifyNoInteractions(agentService);
    }

    @Test
    void oversizedStringPayloadIs413() {
        AgentImportService small =
                new AgentImportService(agentService, catalog, tenantLimits, true, 1024L);
        ObjectNode env = MAPPER.createObjectNode();
        env.put("source", "auto");
        env.put("payload", "y".repeat(5_000)); // within the envelope slack, over the payload cap

        assertThatThrownBy(() -> small.preview(env.toString()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void deeplyNestedJsonIsRejectedAs400WithoutEchoingTheContent() {
        StringBuilder sb = new StringBuilder("{\"source\":\"auto\",\"payload\":");
        int depth = AgentImportService.MAX_NESTING_DEPTH + 40;
        for (int i = 0; i < depth; i++) {
            sb.append("{\"SECRET_MARKER\":");
        }
        sb.append("1");
        for (int i = 0; i < depth; i++) {
            sb.append("}");
        }
        sb.append("}");

        assertThatThrownBy(() -> service.preview(sb.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested too deeply")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET_MARKER"));
    }

    @Test
    void malformedAndWronglyShapedRequestsAre400() {
        for (String body :
                new String[] {
                    null,
                    "",
                    "   ",
                    "not json",
                    "[]",
                    "\"x\"",
                    "{}",
                    "{\"payload\":null}",
                    "{\"payload\":5}",
                    "{\"payload\":[1]}",
                    "{\"source\":7,\"payload\":{}}",
                    "{\"source\":\"auto\",\"payload\":{}}",
                    "{\"source\":\"nope\",\"payload\":{\"instructions\":\"x\"}}"
                }) {
            assertThatThrownBy(() -> service.preview(body))
                    .as("body=%s", body)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(agentService);
    }

    @Test
    void sourceCodePastedAsTextGetsAnExplanationNotAGuess() {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("source", "openai-agents");
        env.put(
                "payload",
                "import { Agent } from '@openai/agents';\nconst a = new Agent({ name: 'x', instructions: 'y' });");

        assertThatThrownBy(() -> service.preview(env.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not JSON")
                .hasMessageContaining("code");
    }

    @Test
    void aFlowiseExportSentAsAnOpenAiSourceIsRefusedNotMisconverted() {
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        envelope(
                                                "openai-assistant",
                                                json("flowise-tool-agent.json"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowise");
        assertThatThrownBy(
                        () -> service.preview(envelope("flowise", json("openai-assistant.json"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai-assistant");
    }

    private static JsonNode parseNode(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void neverCreatesWhenOnlyPreviewing() {
        service.preview(envelope("auto", json("flowise-agentflow-v2.json")));
        verify(agentService, never()).create(any());
    }
}
