package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.billing.resolution.ResolutionController;
import com.agenticai.core.billing.resolution.ResolutionEventRepository;
import com.agenticai.core.common.resolution.ChannelSessionResolutionSweep;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The resolution meter's tenant-table reads and writes against a real Postgres with two tenant
 * schemas, A and B. {@code resolution_event} is a tenant table that the billing code reaches with a
 * plain {@code JdbcTemplate} and unqualified names, so it is only correct when the statement joins
 * the JPA-bound, schema-scoped connection (which needs an open Spring transaction). This class is
 * deliberately NOT {@code @Transactional}: it runs the real HTTP-layer controller and the real
 * scheduled sweep, which are the callers that must open that transaction themselves.
 */
@Tag("integration")
class ResolutionTenantScopeIT extends AbstractContextIT {

    private static final String A = "tenant_it_resolution_a";
    private static final String B = "tenant_it_resolution_b";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolutionController controller;
    @Autowired private ResolutionEventRepository repository;
    @Autowired private ChannelSessionResolutionSweep sweep;
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
            jdbc.execute("TRUNCATE " + schema + ".resolution_event CASCADE");
            jdbc.execute("TRUNCATE " + schema + ".channel_session CASCADE");
            jdbc.execute("DELETE FROM " + schema + ".agent");
        }
        // A statement that escapes tenant scoping lands in the default schema; keep it empty so any
        // such write is visible, and so a leaked row can never masquerade as a tenant's own.
        jdbc.execute("TRUNCATE public.resolution_event CASCADE");
        agentA = UUID.randomUUID();
        insertAgent(A, agentA);
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
        assertThat(eventCount("public")).as("rows leaked into the default schema").isZero();
    }

    private void as(String schema) {
        TenantContext.set(TenantContext.of(schema, "it-user", Set.of()));
    }

    private void insertAgent(String schema, UUID id) {
        jdbc.update(
                "INSERT INTO "
                        + schema
                        + ".agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
                        + " knowledge_base_enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,now(),now())",
                id,
                "Support bot",
                "p",
                "ANTHROPIC",
                "claude-x",
                1000,
                0.2,
                false);
    }

    private void insertEvent(String schema, String dedupeKey) {
        jdbc.update(
                "INSERT INTO "
                        + schema
                        + ".resolution_event (id, conversation_key, agent_id, channel, outcome, evidence,"
                        + " confidence, billable, resolved_at, dedupe_key) VALUES (?,?,?,?,?,?,?,?,now(),?)",
                UUID.randomUUID(),
                "conv-" + dedupeKey,
                agentA,
                "WHATSAPP",
                "RESOLVED_AI",
                "EXPLICIT_API",
                1.0,
                false,
                dedupeKey);
    }

    private int eventCount(String schema) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".resolution_event", Integer.class);
    }

    @Test
    void listAndSummaryOnlyReturnTheCallingTenantsEvents() throws Exception {
        insertEvent(A, "a-1");
        insertEvent(B, "b-1");
        insertEvent(B, "b-2");

        as(A);
        mvc.perform(get("/api/tenant/resolutions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].conversationKey").value("conv-a-1"));
        mvc.perform(get("/api/tenant/resolutions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(1));

        as(B);
        mvc.perform(get("/api/tenant/resolutions"))
                .andExpect(jsonPath("$.events.length()").value(2));
        mvc.perform(get("/api/tenant/resolutions/summary"))
                .andExpect(jsonPath("$.totalEvents").value(2));
    }

    @Test
    void recordAndDisputeLandInTheCallingTenantsSchema() throws Exception {
        as(A);
        String body =
                mapper.writeValueAsString(
                        java.util.Map.of(
                                "conversationKey", "conv-1",
                                "agentId", agentA.toString(),
                                "outcome", "RESOLVED_AI",
                                "dedupeKey", "explicit-1"));
        mvc.perform(
                        post("/api/tenant/resolutions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(true));
        assertThat(eventCount(A)).isEqualTo(1);
        assertThat(eventCount(B)).isZero();

        UUID id = jdbc.queryForObject("SELECT id FROM " + A + ".resolution_event", UUID.class);

        // Tenant B cannot dispute A's event: the id is unknown to its schema.
        as(B);
        mvc.perform(
                        post("/api/tenant/resolutions/" + id + "/dispute")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"not mine\"}"))
                .andExpect(status().isNotFound());

        as(A);
        mvc.perform(
                        post("/api/tenant/resolutions/" + id + "/dispute")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"wrong\"}"))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT disputed_at IS NOT NULL FROM " + A + ".resolution_event",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void sweepRecordsIdleSessionsInTheirOwnTenantSchemaOnly() {
        insertIdleSession(A);
        insertIdleSession(B);

        sweep.sweep();

        assertThat(eventCount(A)).isEqualTo(1);
        assertThat(eventCount(B)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM "
                                        + A
                                        + ".resolution_event WHERE conversation_key = ?",
                                Integer.class,
                                "cs:" + sessionIdOf(A)))
                .isEqualTo(1);
    }

    /**
     * The repository is only safe inside a transaction; this documents the contract it relies on.
     */
    @Test
    void repositoryReadOutsideATransactionIsNotTenantScoped() {
        insertEvent(A, "a-1");
        as(A);
        Instant now = Instant.now();
        assertThat(repository.listPage(now.minus(1, ChronoUnit.DAYS), now.plusSeconds(60), 10, 0))
                .as("a bare JdbcTemplate call resolves against the default schema")
                .isEmpty();
    }

    private UUID sessionIdOf(String schema) {
        return jdbc.queryForObject("SELECT id FROM " + schema + ".channel_session", UUID.class);
    }

    private void insertIdleSession(String schema) {
        UUID agent = UUID.randomUUID();
        insertAgent(schema, agent);
        Timestamp longAgo = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        jdbc.update(
                "INSERT INTO "
                        + schema
                        + ".channel_session (id, channel, external_id, session_id, agent_id, created_at,"
                        + " last_message_at, paired, pairing_notice_sent, ai_disclosure_sent)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                "WHATSAPP",
                "ext-" + schema,
                "sess-" + schema,
                agent,
                longAgo,
                longAgo,
                true,
                true,
                true);
    }
}
