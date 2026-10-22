package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.common.roi.RoiBudgetService;
import com.agenticai.core.common.roi.RoiController;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every {@code /api/tenant/roi/**} endpoint against a real Postgres with two tenant schemas, A and
 * B. Each test seeds data in A and asserts B neither sees it nor can change it. The HTTP layer is a
 * standalone MockMvc over the real controller, services and SQL; authentication and the permission
 * rules are not exercised here (they are pinned by {@code SecurityRuleSnapshotTest}).
 */
@Tag("integration")
// Free mode (the seeded default) resolves every tenant to UNLIMITED, which has no monthly cap; the
// budget test turns it off.
@TestPropertySource(properties = "app.free-mode.cache-ms=0")
class RoiApiIT extends AbstractContextIT {

    private static final String A = "tenant_it_roi_a";
    private static final String B = "tenant_it_roi_b";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoiController controller;
    @Autowired private RoiBudgetService budget;
    @Autowired private ObjectMapper mapper;

    private MockMvc mvc;
    private final String today = LocalDate.now(ZoneOffset.UTC).toString();
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
                                "conversation_correction", "roi_budget_alert",
                        "resolution_event", "turn_feedback", "approval_request", "llm_spend_month",
                                "notification"
                    }) {
                jdbc.execute("TRUNCATE " + schema + "." + table + " CASCADE");
            }
            jdbc.execute("DELETE FROM " + schema + ".agent");
        }
        agentA = UUID.randomUUID();
        insertAgent(A, agentA, "Support bot");
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
        setFreeMode(true); // the seeded default, for any other test class sharing this database
    }

    private void setFreeMode(boolean on) {
        jdbc.update(
                "UPDATE control_plane.platform_setting SET value = ? WHERE key = 'platform.free-mode.enabled'",
                String.valueOf(on));
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

    private void insertCall(
            String schema,
            UUID agent,
            String provider,
            String model,
            long in,
            long out,
            boolean ok) {
        jdbc.update(
                "INSERT INTO "
                        + schema
                        + ".llm_call_log (id, agent_id, session_id, provider_name, model, prompt_tokens,"
                        + " completion_tokens, total_tokens, success, latency_ms, started_at) VALUES (?,?,?,?,?,?,?,?,?,?,now())",
                UUID.randomUUID(),
                agent,
                "s-" + UUID.randomUUID(),
                provider,
                model,
                in,
                out,
                in + out,
                ok,
                10L);
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static final String PRICE =
            """
        {"providerName":"ANTHROPIC","modelPrefix":"claude","inputPerMillion":3,"outputPerMillion":15}""";

    @Test
    void pricesAreVersionedPerTenantAndNeverSeededOrShared() throws Exception {
        as(A);
        mvc.perform(get("/api/tenant/roi/prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices.length()").value(0));
        JsonNode created =
                json(
                        mvc.perform(
                                        post("/api/tenant/roi/prices")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(PRICE))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.setBy").value("it-user")));
        mvc.perform(
                        post("/api/tenant/roi/prices")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"providerName\":\"ANTHROPIC\",\"modelPrefix\":\"claude\",\"inputPerMillion\":4,\"outputPerMillion\":20,\"effectiveFrom\":\"2099-01-01\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenant/roi/prices"))
                .andExpect(jsonPath("$.prices.length()").value(2));

        as(B);
        mvc.perform(get("/api/tenant/roi/prices"))
                .andExpect(jsonPath("$.prices.length()").value(0));
        mvc.perform(delete("/api/tenant/roi/prices/" + created.get("id").asString()))
                .andExpect(status().isNotFound());

        as(A);
        mvc.perform(
                        post("/api/tenant/roi/prices")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"providerName\":\"ANTHROPIC\",\"modelPrefix\":\"claude\",\"inputPerMillion\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/tenant/roi/prices/" + created.get("id").asString()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tenant/roi/prices"))
                .andExpect(jsonPath("$.prices.length()").value(1));
    }

    @Test
    void costsUseOnlyTheTenantsOwnPricesAndReportUnpricedModelsInsteadOfGuessing()
            throws Exception {
        insertCall(A, agentA, "ANTHROPIC", "claude-sonnet", 1_000_000, 1_000_000, true);
        insertCall(A, agentA, "OPENAI", "gpt-unlisted", 500, 500, true);
        insertCall(B, null, "ANTHROPIC", "claude-sonnet", 9_000_000, 9_000_000, true);
        as(A);
        mvc.perform(
                        post("/api/tenant/roi/prices")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PRICE))
                .andExpect(status().isOk());

        JsonNode report =
                json(
                        mvc.perform(get("/api/tenant/roi/costs?groupBy=agent"))
                                .andExpect(status().isOk()));
        assertThat(report.at("/total/estimatedCostUsd").decimalValue())
                .isEqualByComparingTo("18"); // 3 + 15
        assertThat(report.at("/total/pricedCalls").asInt()).isEqualTo(1);
        assertThat(report.at("/total/unpricedCalls").asInt()).isEqualTo(1);
        assertThat(report.at("/total/costComplete").asBoolean()).isFalse();
        assertThat(report.at("/rows/0/label").asString()).isEqualTo("Support bot");
        assertThat(report.get("costBasis").asString()).contains("estimated");

        JsonNode prices = json(mvc.perform(get("/api/tenant/roi/prices")));
        assertThat(prices.at("/unpricedModels/0/model").asString()).isEqualTo("gpt-unlisted");
        assertThat(prices.at("/unpricedModels/0/reason").asString()).isEqualTo("NO_PRICE");

        for (String dim : new String[] {"workflow", "channel", "owner", "day"}) {
            mvc.perform(get("/api/tenant/roi/costs?groupBy=" + dim)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/tenant/roi/costs?groupBy=nonsense"))
                .andExpect(status().isBadRequest());

        as(B); // B has calls but no prices: its heavy usage is unpriced, and A's price does
        // not leak in
        JsonNode b = json(mvc.perform(get("/api/tenant/roi/costs")).andExpect(status().isOk()));
        assertThat(b.at("/total/estimatedCostUsd").decimalValue()).isEqualByComparingTo("0");
        assertThat(b.at("/total/unpricedCalls").asInt()).isEqualTo(1);
    }

    @Test
    void assumptionsAreOptionalNeverDefaultedAndTenantScoped() throws Exception {
        as(A);
        mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/assumptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valuePerResolution").doesNotExist())
                .andExpect(jsonPath("$.basis").value("based on your assumptions"));
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/assumptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"valuePerResolution\":6}"))
                .andExpect(status().isBadRequest()); // currency required
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/assumptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"valuePerResolution\":6,\"valueCurrency\":\"usd\",\"minutesSavedPerResolution\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueCurrency").value("USD"));

        as(B);
        mvc.perform(get("/api/tenant/roi/agents/" + agentA + "/assumptions"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/assumptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"valuePerResolution\":1,\"valueCurrency\":\"USD\"}"))
                .andExpect(status().isNotFound());

        as(A);
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/assumptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valuePerResolution").doesNotExist());
    }

    @Test
    void outcomesFeedTheAgentTableAndRatesAreNullWithoutData() throws Exception {
        as(A);
        // an agent with no outcomes: every rate is null (not 0), no ROI
        JsonNode empty =
                json(mvc.perform(get("/api/tenant/roi/agents")).andExpect(status().isOk()));
        assertThat(empty.at("/agents/0/handoverRate").isNull()).isTrue();
        assertThat(empty.at("/agents/0/estimatedCostPerResolvedUsd").isNull()).isTrue();
        assertThat(empty.at("/agents/0/roiNote").asString()).isEqualTo("NO_ASSUMPTIONS");

        mvc.perform(
                        post("/api/tenant/roi/prices")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PRICE))
                .andExpect(status().isOk());
        insertCall(A, agentA, "ANTHROPIC", "claude-sonnet", 1_000_000, 0, true); // $3
        mvc.perform(
                        put("/api/tenant/roi/agents/" + agentA + "/assumptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"valuePerResolution\":5,\"valueCurrency\":\"USD\",\"minutesSavedPerResolution\":10}"))
                .andExpect(status().isOk());

        for (String[] o :
                new String[][] {
                    {"c1", "RESOLVED"},
                    {"c2", "RESOLVED"},
                    {"c3", "HANDED_TO_HUMAN"},
                    {"c4", "ABANDONED"},
                    {"c4", "ABANDONED"},
                    {"c1", "CORRECTED_BY_HUMAN"}
                }) {
            mvc.perform(
                            post("/api/tenant/roi/outcomes")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"conversationKey\":\""
                                                    + o[0]
                                                    + "\",\"agentId\":\""
                                                    + agentA
                                                    + "\",\"outcome\":\""
                                                    + o[1]
                                                    + "\"}"))
                    .andExpect(status().isOk());
        }
        // duplicate mark is a no-op, not an error
        mvc.perform(
                        post("/api/tenant/roi/outcomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"conversationKey\":\"c4\",\"agentId\":\""
                                                + agentA
                                                + "\",\"outcome\":\"ABANDONED\"}"))
                .andExpect(jsonPath("$.recorded").value(false));
        mvc.perform(
                        post("/api/tenant/roi/outcomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"conversationKey\":\"c9\",\"agentId\":\""
                                                + agentA
                                                + "\",\"outcome\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        // an EDIT from turn_feedback also counts as a human correction (conversation key == chat
        // session id)
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".turn_feedback (id, agent_id, session_id, turn_ref, signal, created_at) VALUES (?,?,?,?,?,now())",
                UUID.randomUUID(),
                agentA,
                "c2",
                "t1",
                "EDIT");
        // approvals: 3 decided (1 rejected) and 1 pending, attributed through requested_by
        for (String st : new String[] {"APPROVED", "APPROVED", "REJECTED", "PENDING"}) {
            jdbc.update(
                    "INSERT INTO "
                            + A
                            + ".approval_request (id, action_type, status, requested_by, created_at, retry_count) VALUES (?,?,?,?,now(),0)",
                    UUID.randomUUID(),
                    "mcp_tool.x",
                    st,
                    "agent:" + agentA + ":session:s");
        }

        JsonNode row =
                json(mvc.perform(get("/api/tenant/roi/agents")).andExpect(status().isOk()))
                        .at("/agents/0");
        assertThat(row.get("conversations").asInt()).isEqualTo(4);
        assertThat(row.get("resolved").asInt()).isEqualTo(2);
        assertThat(row.get("handedToHuman").asInt()).isEqualTo(1);
        assertThat(row.get("humanCorrected").asInt())
                .isEqualTo(2); // c1 explicit + c2 feedback EDIT
        assertThat(row.get("handoverRate").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(row.get("humanCorrectionRate").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(row.get("approvalDenialRate").decimalValue()).isEqualByComparingTo("0.3333");
        assertThat(row.get("estimatedCostUsd").decimalValue()).isEqualByComparingTo("3");
        assertThat(row.get("estimatedCostPerResolvedUsd").decimalValue())
                .isEqualByComparingTo("1.5");
        assertThat(row.get("valueTotal").decimalValue()).isEqualByComparingTo("10");
        assertThat(row.get("netValue").decimalValue()).isEqualByComparingTo("7");
        assertThat(row.get("roiPercent").decimalValue()).isEqualByComparingTo("233.3");

        String csv =
                mvc.perform(get("/api/tenant/roi/agents.csv"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(csv)
                .contains("roi_percent_based_on_your_assumptions")
                .contains("Support bot")
                .contains("233.3");

        as(B); // B cannot mark outcomes on A's agent, and sees none of A's rows
        mvc.perform(
                        post("/api/tenant/roi/outcomes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"conversationKey\":\"x\",\"agentId\":\""
                                                + agentA
                                                + "\",\"outcome\":\"RESOLVED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tenant/roi/agents"))
                .andExpect(jsonPath("$.agents.length()").value(0));
        assertThat(
                        mvc.perform(get("/api/tenant/roi/agents.csv"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .doesNotContain("Support bot");
    }

    @Test
    void budgetBarAndOneShotThresholdAlerts() throws Exception {
        setFreeMode(false);
        as(A);
        LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        JsonNode none = json(mvc.perform(get("/api/tenant/roi/budget")).andExpect(status().isOk()));
        assertThat(none.get("capConfigured").asBoolean())
                .as(none.toString())
                .isTrue(); // PREMIUM: the cap the gateway enforces
        setFreeMode(true);
        assertThat(
                        json(mvc.perform(get("/api/tenant/roi/budget")))
                                .get("capConfigured")
                                .asBoolean())
                .as("free mode has no cap")
                .isFalse();
        assertThat(budget.raiseThresholdAlerts()).isZero();
        setFreeMode(false);
        assertThat(budget.raiseThresholdAlerts()).isZero();

        long cap = none.get("capUsd").decimalValue().longValue();
        BigDecimalHelper.setSpend(jdbc, A, month, cap * 0.8);
        assertThat(budget.raiseThresholdAlerts()).isEqualTo(1);
        assertThat(budget.raiseThresholdAlerts()).as("80% fires once a month").isZero();
        BigDecimalHelper.setSpend(jdbc, A, month, cap);
        assertThat(budget.raiseThresholdAlerts()).isEqualTo(1);
        assertThat(budget.raiseThresholdAlerts()).isZero();

        JsonNode b = json(mvc.perform(get("/api/tenant/roi/budget")));
        assertThat(b.get("alertsFired")).hasSize(2);
        assertThat(b.get("percentUsed").decimalValue()).isEqualByComparingTo("100.0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM "
                                        + A
                                        + ".notification WHERE category = 'BILLING'",
                                Integer.class))
                .isEqualTo(2);

        as(B); // B's own counter is untouched: no alert history, no notification
        assertThat(json(mvc.perform(get("/api/tenant/roi/budget"))).get("alertsFired")).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM " + B + ".notification", Integer.class))
                .isZero();
    }

    /** Writes the month counter the gateway reads. */
    private static final class BigDecimalHelper {
        static void setSpend(JdbcTemplate jdbc, String schema, LocalDate month, double usd) {
            jdbc.update(
                    "INSERT INTO "
                            + schema
                            + ".llm_spend_month (month_start, cost_usd) VALUES (?, ?)"
                            + " ON CONFLICT (month_start) DO UPDATE SET cost_usd = EXCLUDED.cost_usd",
                    java.sql.Date.valueOf(month),
                    java.math.BigDecimal.valueOf(usd));
        }
    }
}
