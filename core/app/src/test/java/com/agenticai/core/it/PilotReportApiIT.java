package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.common.roi.RoiController;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The pilot baseline and report endpoints against a real Postgres with two tenant schemas. Covers a
 * zero-volume agent, a missing baseline, missing prices, the demo watermark, and that tenant B can
 * neither read nor change what tenant A entered nor build a report for A's agent.
 */
@Tag("integration")
@TestPropertySource(properties = "app.free-mode.cache-ms=0")
class PilotReportApiIT extends AbstractContextIT {

    private static final String A = "tenant_it_pilot_a";
    private static final String B = "tenant_it_pilot_b";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoiController controller;
    @Autowired private ObjectMapper mapper;

    private MockMvc mvc;
    private UUID agentA;

    @BeforeEach
    void setUp() {
        for (String schema : new String[] {A, B}) {
            if (tenants.findBySchemaName(schema).isEmpty()) {
                Tenant t =
                        new Tenant(
                                schema,
                                TenantPlan.PREMIUM,
                                schema,
                                "agentic-ai",
                                AccountType.B2B,
                                ExperienceMode.SIMPLE);
                t.markActive();
                tenants.save(t);
            }
        }
        assertThat(migrations.migrateAllActiveTenants()).as("schemas that failed").isEmpty();
        for (String schema : new String[] {A, B}) {
            for (String table :
                    new String[] {
                        "llm_call_log", "llm_price", "agent_value_assumption",
                                "agent_pilot_baseline",
                        "demo_dataset", "conversation_correction", "resolution_event",
                                "turn_feedback",
                        "approval_request"
                    }) {
                jdbc.execute("TRUNCATE " + schema + "." + table + " CASCADE");
            }
            jdbc.execute("DELETE FROM " + schema + ".agent");
        }
        agentA = UUID.randomUUID();
        insertAgent(A, agentA, "Reminder bot");
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setMessageConverters(
                                new ByteArrayHttpMessageConverter(),
                                new MappingJackson2HttpMessageConverter(mapper))
                        .build();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void as(String schema) {
        TenantContext.set(TenantContext.of(schema, "it-user", Set.of()));
    }

    private void insertAgent(String schema, UUID id, String name) {
        jdbc.update(
                "INSERT INTO "
                        + schema
                        + ".agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
                        + " knowledge_base_enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,now(),now())",
                id,
                name,
                "p",
                "ANTHROPIC",
                "claude-x",
                1000,
                0.2,
                false);
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static final String BASELINE =
            "{\"jobType\":\"APPOINTMENT_REMINDERS\",\"baselinePeriod\":\"Aug 2026\",\"monthlyVolume\":300,"
                    + "\"avgHandlingMinutes\":4.5,\"missedRate\":0.22,\"avgValuePerConversion\":1200,\"valueCurrency\":\"inr\"}";

    @Test
    void zeroVolumeAgentGetsAReportWithNoRatesAndNoZeros() throws Exception {
        as(A);
        JsonNode r =
                json(
                        mvc.perform(
                                        get(
                                                "/api/tenant/roi/agents/"
                                                        + agentA
                                                        + "/pilot-report?format=json"))
                                .andExpect(status().isOk()));
        assertThat(r.get("conversations").asInt()).isZero();
        assertThat(r.get("resolvedRate").isNull()).isTrue();
        assertThat(r.get("handoverRate").isNull()).isTrue();
        assertThat(r.get("humanCorrectionRate").isNull()).isTrue();
        assertThat(r.get("medianResponseSeconds").isNull()).isTrue();
        assertThat(r.get("roiPercent").isNull()).isTrue();
        assertThat(r.get("demo").asBoolean()).isFalse();
        assertThat(r.get("modelCalls").asInt()).isZero();
        assertThat(r.get("estimatedCostUsd").isNull())
                .as("no calls recorded is a dash, not zero cost")
                .isTrue();
        assertThat(r.get("costPerResolvedUsd").isNull()).isTrue();
        String html =
                mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/pilot-report"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("No conversations with a recorded outcome")
                .contains("Assumptions");
    }

    @Test
    void missingBaselineAndMissingPricesAreReportedAsMissing() throws Exception {
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".llm_call_log (id, agent_id, session_id, provider_name, model, prompt_tokens,"
                        + " completion_tokens, total_tokens, success, latency_ms, started_at) VALUES (?,?,?,?,?,?,?,?,?,?,now())",
                UUID.randomUUID(),
                agentA,
                "s1",
                "ANTHROPIC",
                "claude-x",
                1000L,
                500L,
                1500L,
                true,
                2000L);
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".llm_call_log (id, agent_id, session_id, provider_name, model, prompt_tokens,"
                        + " completion_tokens, total_tokens, success, latency_ms, started_at) VALUES (?,?,?,?,?,?,?,?,?,?,now())",
                UUID.randomUUID(),
                agentA,
                "s2",
                "ANTHROPIC",
                "claude-x",
                1000L,
                500L,
                1500L,
                true,
                4000L);
        as(A);
        JsonNode r =
                json(
                        mvc.perform(
                                        get(
                                                "/api/tenant/roi/agents/"
                                                        + agentA
                                                        + "/pilot-report?format=json"))
                                .andExpect(status().isOk()));
        assertThat(r.get("baseline").get("monthlyVolume").isNull()).isTrue();
        assertThat(r.get("baseline").get("source").asText()).isEqualTo("tenant-entered");
        assertThat(r.get("costComplete").asBoolean())
                .isFalse(); // no llm_price row: unpriced, not guessed
        assertThat(
                        r.get("estimatedCostUsd").isNull()
                                || r.get("estimatedCostUsd").decimalValue().signum() == 0)
                .isTrue();
        assertThat(r.get("medianResponseSeconds").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(r.get("responseSamples").asInt()).isEqualTo(2);
        assertThat(r.get("roiNote").asText()).isEqualTo("NO_ASSUMPTIONS");
        assertThat(r.get("modelCalls").asInt()).isEqualTo(2);
        StringBuilder labels = new StringBuilder();
        r.get("assumptions")
                .forEach(
                        a ->
                                labels.append(a.get("label").asText())
                                        .append('|')
                                        .append(a.get("value").asText())
                                        .append(';'));
        assertThat(labels)
                .as("an unpriced model is listed as missing, not silently dropped")
                .contains("none entered");
    }

    @Test
    void baselineIsStoredAsTypedValidatedAndListedInTheAssumptionsBox() throws Exception {
        as(A);
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BASELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueCurrency").value("INR"))
                .andExpect(jsonPath("$.source").value("tenant-entered"))
                .andExpect(jsonPath("$.enteredBy").value("it-user"));
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"missedRate\":25}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"jobType\":\"WORLD_PEACE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"avgValuePerConversion\":10}"))
                .andExpect(status().isBadRequest()); // currency required
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"monthlyVolume\":-1}"))
                .andExpect(status().isBadRequest());
        // column limits: rejected with a 400, never rounded or turned into a 500
        for (String bad :
                new String[] {
                    "{\"avgHandlingMinutes\":100000000}",
                    "{\"avgHandlingMinutes\":0.005}",
                    "{\"missedRate\":0.12345}",
                    "{\"monthlyVolume\":1.5}"
                }) {
            mvc.perform(
                            put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(bad))
                    .andExpect(status().isBadRequest());
        }
        JsonNode r =
                json(
                        mvc.perform(
                                        get(
                                                "/api/tenant/roi/agents/"
                                                        + agentA
                                                        + "/pilot-report?format=json"))
                                .andExpect(status().isOk()));
        assertThat(r.get("baseline").get("monthlyVolume").asInt()).isEqualTo(300);
        StringBuilder labels = new StringBuilder();
        r.get("assumptions").forEach(a -> labels.append(a.get("label").asText()).append('|'));
        assertThat(labels)
                .contains("Baseline monthly volume")
                .contains("Baseline missed rate")
                .contains("Baseline average handling time");
        // all-empty body clears it
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyVolume").doesNotExist());
    }

    @Test
    void tenantBCannotReadChangeOrReportOnTenantAsAgent() throws Exception {
        as(A);
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BASELINE))
                .andExpect(status().isOk());
        as(B);
        mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/baseline"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/baseline")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"monthlyVolume\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/pilot-report?format=json"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/pilot-report?format=pdf"))
                .andExpect(status().isNotFound());
        // B's own agent with the same id space: nothing of A's leaks into B's report
        UUID agentB = UUID.randomUUID();
        insertAgent(B, agentB, "B bot");
        JsonNode rb =
                json(
                        mvc.perform(
                                        get(
                                                "/api/tenant/roi/agents/"
                                                        + agentB
                                                        + "/pilot-report?format=json"))
                                .andExpect(status().isOk()));
        assertThat(rb.get("agentName").asText()).isEqualTo("B bot");
        assertThat(rb.get("baseline").get("monthlyVolume").isNull()).isTrue();
        // and A's baseline is intact
        as(A);
        mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/baseline"))
                .andExpect(jsonPath("$.monthlyVolume").value(300));
    }

    @Test
    void demoMarkerIsPerTenantAndWatermarksHtmlAndPdf() throws Exception {
        as(A);
        mvc.perform(get("/api/tenant/roi/demo")).andExpect(jsonPath("$.demo").value(false));
        jdbc.update("INSERT INTO " + A + ".demo_dataset (id, label) VALUES (1, 'Example clinic')");
        mvc.perform(get("/api/tenant/roi/demo"))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.label").value("Example clinic"));
        String html =
                mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/pilot-report"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html).contains("class=\"wm\"").contains("NOT A CUSTOMER RESULT");
        byte[] pdf =
                mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/pilot-report?format=pdf"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(d)).contains("EXAMPLE");
        }
        as(B);
        mvc.perform(get("/api/tenant/roi/demo")).andExpect(jsonPath("$.demo").value(false));
    }

    @Test
    void badFormatAndBadRangeAreRejected() throws Exception {
        as(A);
        mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/pilot-report?format=docx"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(
                                "/api/tenant/roi/agents/"
                                        + agentA
                                        + "/pilot-report?from=2026-09-30&to=2026-09-01"))
                .andExpect(status().isBadRequest());
    }
}
