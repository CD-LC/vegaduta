package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.common.privacy.AuditExportService;
import com.agenticai.core.common.privacy.ConsentGateService;
import com.agenticai.core.common.privacy.ConsentLedger;
import com.agenticai.core.common.privacy.ConsentPolicyService;
import com.agenticai.core.common.privacy.ContactPrivacyService;
import com.agenticai.core.common.privacy.PurposeService;
import com.agenticai.core.common.privacy.RetentionPolicyService;
import com.agenticai.core.common.privacy.RetentionPurger;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import reactor.core.publisher.Flux;

/**
 * Shared wiring for the DPDP-control integration tests: two migrated tenant schemas on a real
 * Postgres, each selected per statement from the {@link TenantContext} (the same mechanism the
 * app's tenant-routing datasource uses), so a service that reached across tenants would show up as a
 * row changed or read in the other schema. Graph memory (Apache AGE), the session-history store
 * (Redis) and object storage are mocked: what is asserted for them is that the erase / purge path
 * calls them, not their own behaviour, which their own tests cover.
 *
 * <p>Split by behaviour area: {@link DpdpControlsIT} (consent and purposes), {@link
 * DpdpRetentionIT} and {@link DpdpErasureAndExportIT}. Each subclass migrates its own fresh
 * database in its {@code @BeforeAll}.
 */
abstract class DpdpControlsFixture {

    protected static final String A = "tenant_it_dpdp_a";
    protected static final String B = "tenant_it_dpdp_b";
    protected static final String CHANNEL = "WHATSAPP";
    protected static final String CONTACT = "+919876543210";
    protected static final String NOTICE_EN = "We process your messages and name to answer support questions.";
    protected static final String NOTICE_HI = "हम सहायता प्रश्नों के उत्तर देने के लिए आपके संदेशों को संसाधित करते हैं।";

    protected static JdbcTemplate jdbc;

    protected AuditLogService audit;
    protected FileStorageService storage;
    protected GraphMemoryService graph;
    protected SessionMemoryService sessionMemory;
    protected ConsentPolicyService policies;
    protected ConsentLedger ledger;
    protected ContactPrivacyService contacts;
    protected ConsentGateService gate;
    protected RetentionPolicyService retention;
    protected RetentionPurger purger;
    protected PurposeService purposes;
    protected AuditExportService auditExport;

    protected final UUID agentA = UUID.randomUUID();
    protected final UUID agentB = UUID.randomUUID();
    protected final String sessionA = UUID.randomUUID().toString();
    protected final String sessionB = UUID.randomUUID().toString();

    /** Sets {@code search_path} from the current TenantContext on every connection it hands out. */
    private static final class TenantRoutingDataSource extends DelegatingDataSource {
        TenantRoutingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection c = super.getConnection();
            String schema = TenantContext.current().map(TenantContext::getTenantId).orElse("public");
            try (Statement st = c.createStatement()) {
                st.execute("SET search_path TO \"" + schema + "\", public");
            }
            return c;
        }
    }

    /** Creates the named database, migrates the platform and both tenant schemas into it. */
    protected static void migrateFreshDatabase(String name) {
        DataSource raw = IntegrationTestContainers.freshDatabase(name);
        PlatformMigrations.migrateBoot(raw);
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(raw);
        migrator.migrate(A);
        migrator.migrate(B);
        jdbc = new JdbcTemplate(new TenantRoutingDataSource(raw));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        audit = mock(AuditLogService.class);
        storage = mock(FileStorageService.class);
        graph = mock(GraphMemoryService.class);
        sessionMemory = mock(SessionMemoryService.class);
        SidecarClient sidecar = mock(SidecarClient.class);
        AgentMemoryService agentMemory = new AgentMemoryService(sidecar, jdbc);
        TenantMemoryService tenantMemory =
                new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);

        policies = new ConsentPolicyService(jdbc);
        ledger = new ConsentLedger(jdbc);
        contacts =
                new ContactPrivacyService(
                        jdbc,
                        audit,
                        ledger,
                        provider(agentMemory),
                        provider(tenantMemory),
                        provider(graph),
                        provider(sessionMemory));
        gate = new ConsentGateService(jdbc, policies, ledger, contacts, audit);
        retention = new RetentionPolicyService(jdbc);
        purger = new RetentionPurger(jdbc, retention, audit, storage, provider(agentMemory), provider(tenantMemory));
        purposes = new PurposeService(jdbc, policies);
        auditExport = new AuditExportService(jdbc, audit);

        for (String t : List.of(A, B)) {
            inTenant(t, () -> wipe());
        }
        inTenant(A, () -> seedBase(agentA, sessionA));
        inTenant(B, () -> seedBase(agentB, sessionB));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    protected long exportCsv(ByteArrayOutputStream out, Instant from, Instant to) {
        try {
            return auditExport.exportCsv(from, to, out, "admin");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    protected long exportJson(ByteArrayOutputStream out, Instant from, Instant to) {
        try {
            return auditExport.exportJson(from, to, out, "admin");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    protected static void inTenant(String tenant, Runnable work) {
        TenantContext previous = TenantContext.current().orElse(null);
        TenantContext.set(TenantContext.of(tenant, "it-admin", Set.of()));
        try {
            work.run();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    protected static String reply(Flux<String> flux) {
        assertThat(flux).isNotNull();
        return String.join("", flux.collectList().block());
    }

    protected static String sha256Hex(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static ConsentPolicyService.Request request(boolean enabled, String enNotice) {
        return new ConsentPolicyService.Request(enabled, "support", "en", Map.of("en", enNotice, "hi", NOTICE_HI), Map.of(), Map.of(), null);
    }

    protected void enableConsent(UUID agent) {
        policies.put(agent, request(true, NOTICE_EN), "admin");
    }

    protected static int count(String table, String externalId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE external_id = ?", Integer.class, externalId);
    }

    protected static void wipe() {
        for (String t : List.of("privacy_consent_record", "privacy_purpose_tag", "privacy_retention_policy", "privacy_consent_policy",
                "channel_message_log", "channel_contact", "channel_session", "agent_memory", "tenant_memory", "knowledge_chunks",
                "knowledge_collection", "voice_call_recording", "lead")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM agent");
    }

    protected static UUID insertAgent() {
        UUID id = UUID.randomUUID();
        insertAgent(id);
        return id;
    }

    protected static void insertAgent(UUID id) {
        jdbc.update("INSERT INTO agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
                + " knowledge_base_enabled, created_at, updated_at) VALUES (?, 'it', 'x', 'OPENAI', 'gpt-4o-mini', 256, 0.2, false, now(), now())", id);
    }

    protected static UUID collection(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_collection (id, name, embedding_model) VALUES (?, ?, 'minilm-l6-v2')", id, name);
        return id;
    }

    /** An agent, and the contact's session with two recent and two older (but < 365 days) messages. */
    protected static void seedBase(UUID agent, String sessionKey) {
        insertAgent(agent);
        UUID sessionRow = UUID.randomUUID();
        jdbc.update("INSERT INTO channel_session (id, channel, external_id, session_id, agent_id, created_at, last_message_at,"
                + " paired, pairing_notice_sent, ai_disclosure_sent) VALUES (?, ?, ?, ?, ?, now(), now(), true, true, true)",
                sessionRow, CHANNEL, CONTACT, sessionKey, agent);
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO channel_message_log (id, channel, external_id, channel_session_id, body, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, now() - (? * interval '1 day'))", UUID.randomUUID(), CHANNEL, CONTACT, sessionRow, "message " + i, i);
        }
    }

    /** Expired and fresh rows of every retention class on top of {@link #seedBase}. */
    protected static void seedOldData(UUID agent, Instant old) {
        // seedBase left four fresh messages; turn two of them into expired ones.
        jdbc.update("UPDATE channel_message_log SET created_at = ? WHERE body IN ('message 0', 'message 1')", java.sql.Timestamp.from(old));
        for (String kind : List.of("old", "new")) {
            java.sql.Timestamp at = java.sql.Timestamp.from("old".equals(kind) ? old : Instant.now());
            jdbc.update("INSERT INTO agent_memory (id, agent_id, subject_key, content, embedding, created_at)"
                    + " VALUES (?, ?, ?, ?, array_fill(0.1::float4, ARRAY[384])::vector, ?)", UUID.randomUUID(), agent, "s-" + kind, kind + " fact", at);
            jdbc.update("INSERT INTO tenant_memory (id, subject_key, content, embedding, created_at)"
                    + " VALUES (?, ?, ?, array_fill(0.1::float4, ARRAY[384])::vector, ?)", UUID.randomUUID(), "s-" + kind, kind + " shared fact", at);
            jdbc.update("INSERT INTO voice_call_recording (id, agent_id, room_name, object_key, status, started_at, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, ?)", UUID.randomUUID(), agent, "room-" + kind, "recordings/" + kind + ".ogg", at, at, at);
        }
    }

    /** Everything an erasure of CONTACT should reach, plus bystanders it must not. */
    protected static void seedContactData(UUID agent, String sessionKey) {
        UUID coll = collection("agent-notes");
        chunk(coll, "Agent memory: note (written by agent " + agent + ", session " + sessionKey + ", at 2026-10-01T00:00:00Z)");
        chunk(coll, "Agent memory: other (written by agent " + agent + ", session other-session, at 2026-10-01T00:00:00Z)");
        chunk(coll, "handbook.pdf");
        jdbc.update("INSERT INTO agent_memory (id, agent_id, subject_key, content, embedding, created_at)"
                + " VALUES (?, ?, ?, 'prefers Hindi', array_fill(0.1::float4, ARRAY[384])::vector, now())", UUID.randomUUID(), agent, CONTACT);
        jdbc.update("INSERT INTO agent_memory (id, agent_id, subject_key, content, embedding, created_at)"
                + " VALUES (?, ?, 'someone-else', 'other', array_fill(0.1::float4, ARRAY[384])::vector, now())", UUID.randomUUID(), agent);
        jdbc.update("INSERT INTO tenant_memory (id, subject_key, content, embedding, created_at)"
                + " VALUES (?, ?, 'lives in Pune', array_fill(0.1::float4, ARRAY[384])::vector, now())", UUID.randomUUID(), CONTACT);
        jdbc.update("INSERT INTO channel_contact (id, channel, identifier, identifier_digits, display_name, source, created_at)"
                + " VALUES (?, ?, ?, '919876543210', 'Priya', 'IMPORT', now())", UUID.randomUUID(), CHANNEL, CONTACT);
        jdbc.update("INSERT INTO lead (id, name, phone, stage, score, created_at, updated_at) VALUES (?, 'Priya', '+91 98765 43210', 'NEW', 0, now(), now())", UUID.randomUUID());
    }

    protected static void chunk(UUID collection, String source) {
        jdbc.update("INSERT INTO knowledge_chunks (id, source, chunk_index, content, embedding, embedding_model, collection_id)"
                + " VALUES (?, ?, 0, 'c', array_fill(0.1::float4, ARRAY[768])::vector, 'minilm-l6-v2', ?)", UUID.randomUUID(), source, collection);
    }

    protected static void insertAudit(String actor, Instant at, String detail) {
        jdbc.update("INSERT INTO audit_log_entry (id, actor_id, action_type, target_type, target_id, outcome, detail, created_at)"
                + " VALUES (?, ?, 'test.action', 't', 'x', 'SUCCESS', ?, ?)", UUID.randomUUID(), actor, detail, java.sql.Timestamp.from(at));
    }
}
