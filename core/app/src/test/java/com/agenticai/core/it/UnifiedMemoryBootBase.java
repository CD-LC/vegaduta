package com.agenticai.core.it;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.memorystore.MemoryEmbedder;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole {@code CoreApplication} (profiles security, knowledge, graph-memory) booted against an
 * EXTERNAL pgvector + AGE Postgres and a Redis-protocol server, with real tenant Flyway, for the
 * unified-memory journey ITs. Needs {@code -Dvegaduta.it.pg.url=...} (see {@link
 * ExternalPostgres}); Redis at {@code -Dvegaduta.it.redis.host/port} (default 127.0.0.1:6379).
 * Skipped when no Postgres is configured. No Docker, no Keycloak, no embedding sidecar: the only
 * fake is the {@link MemoryEmbedder}, a deterministic bag-of-words vector.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=security,knowledge,graph-memory",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak.invalid/realms/test",
            "app.web.public-url=http://app.invalid",
            "app.encryption.key=hqfzJpKYl+OWYpyllCtUBEB0rS/iZUAu231ls90mBb0=",
            "app.storage.type=local",
            "app.storage.local.path=${java.io.tmpdir}/vegaduta-it-storage",
            "management.tracing.sampling.probability=0.0",
            "management.otlp.tracing.export.enabled=false"
        })
abstract class UnifiedMemoryBootBase {

    @Autowired protected TenantRepository tenants;
    @Autowired protected TenantMigrationService migrations;
    @Autowired protected AgentRepository agents;
    @Autowired protected TransactionTemplate tx;

    /**
     * Skips the class unless an external Postgres is configured; call from a static
     * {@code @BeforeAll}.
     */
    static void requirePostgres() {
        ExternalPostgres.requireOrSkip();
    }

    /** Points Spring at a fresh database on the external server and at the configured Redis. */
    static void register(DynamicPropertyRegistry registry, String database) {
        DriverManagerDataSource ds = ExternalPostgres.bootDatabase(database);
        registry.add("spring.datasource.url", ds::getUrl);
        registry.add("spring.datasource.username", ds::getUsername);
        registry.add(
                "spring.datasource.password",
                () ->
                        ds.getPassword() == null || ds.getPassword().isBlank()
                                ? "trust-auth-placeholder"
                                : ds.getPassword());
        registry.add(
                "spring.data.redis.host",
                () -> System.getProperty("vegaduta.it.redis.host", "127.0.0.1"));
        registry.add(
                "spring.data.redis.port",
                () -> System.getProperty("vegaduta.it.redis.port", "6379"));
    }

    /** An ACTIVE tenant with a fully migrated schema (idempotent). */
    protected void tenantSchema(String schema) {
        if (tenants.findBySchemaName(schema).isEmpty()) {
            Tenant tenant =
                    new Tenant(
                            schema,
                            TenantPlan.FREE,
                            schema,
                            "agentic-ai",
                            AccountType.B2B,
                            ExperienceMode.SIMPLE);
            tenant.markActive();
            tenants.save(tenant);
        }
        org.assertj.core.api.Assertions.assertThat(migrations.migrateAllActiveTenants())
                .as("schemas that failed to migrate")
                .isEmpty();
    }

    /** A real agent row in the CURRENT tenant (the caller has set the TenantContext). */
    protected UUID newAgent(String name) {
        return tx.execute(
                status ->
                        agents.save(
                                        new Agent(
                                                name,
                                                "You are helpful.",
                                                ProviderName.OPENAI,
                                                "gpt-4o-mini",
                                                1024,
                                                0.2,
                                                1.0,
                                                true,
                                                ExperienceMode.SIMPLE))
                                .getId());
    }

    /**
     * Deterministic bag-of-words embedder: every word hashes to a bucket, the vector is
     * L2-normalised, so texts that share words are near each other and unrelated texts are
     * orthogonal. Replaces the sidecar embedder.
     */
    static float[] embed(String text) {
        float[] v = new float[MemoryEmbedder.DIMENSION];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) {
                v[Math.floorMod(word.hashCode(), v.length)] += 1f;
            }
        }
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= (float) norm;
        }
        return v;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEmbedderConfiguration {
        @Bean
        @Primary
        MemoryEmbedder journeyEmbedder() {
            return UnifiedMemoryBootBase::embed;
        }
    }
}
