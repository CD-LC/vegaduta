package com.agenticai.core.it.memeval;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationService;
import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationSweep;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsService;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booted application as the memory eval sees it: the real beans of the memory pipeline plus the tenant lifecycle the
 * eval needs (a fresh tenant schema per case, dropped after it). The beans are public fields the IT fills from the
 * Spring context, so the harness classes need no Spring wiring of their own.
 *
 * <p><b>Tenant provisioning is a clone, not a Flyway run.</b> The first tenant is migrated by the real tenant Flyway
 * (275 migrations); every later one is created with {@code CREATE TABLE ... (LIKE template INCLUDING ALL)} for each
 * table of that template, in one statement, because a Flyway run per (case, arm, repeat) would be hours. A clone has the
 * columns, defaults, check constraints and indexes of the migrated schema but NOT its foreign keys, triggers or
 * functions, so anything that relies on those (for example the history tables' immutability triggers) is not exercised.
 */
public final class PlatformEvalWorld {

    static final String TEMPLATE_SCHEMA = "tenant_memeval_template";
    private static final Pattern SAFE_SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final String ROLE_TENANT_ADMIN = "ROLE_tenant-admin";

    public JdbcTemplate jdbc;
    public TenantRepository tenants;
    public TenantMigrationService migrations;
    public AgentRepository agents;
    public TransactionTemplate tx;
    public SettingsService settings;
    public AgentMemoryService agentMemory;
    public TenantMemoryService tenantMemory;
    public MemoryItemReviewService review;
    public AgentChatService chat;
    public MemoryConsolidationService consolidation;
    public MemoryConsolidationSweep sweep;

    private volatile List<String> templateTables;

    /** Creates the migrated template schema once (real tenant Flyway) and remembers its tables. */
    public synchronized void ensureTemplate() {
        if (templateTables != null) {
            return;
        }
        TenantContext.clear();
        registerTenant(TEMPLATE_SCHEMA);
        List<String> failed = migrations.migrateAllActiveTenants();
        if (!failed.isEmpty()) {
            throw new IllegalStateException("tenant schemas that failed to migrate: " + failed);
        }
        templateTables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'"
                + " AND table_name <> 'flyway_schema_history' ORDER BY table_name",
            String.class, TEMPLATE_SCHEMA);
    }

    /** A new, empty, ACTIVE tenant with the template's tables. */
    public void createTenant(String schema) {
        ensureTemplate();
        requireSafe(schema);
        TenantContext.clear();
        jdbc.execute("DO $$ DECLARE t text; BEGIN EXECUTE 'CREATE SCHEMA " + schema + "'; "
            + "FOR t IN SELECT table_name FROM information_schema.tables WHERE table_schema = '" + TEMPLATE_SCHEMA
            + "' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' LOOP "
            + "EXECUTE format('CREATE TABLE " + schema + ".%I (LIKE " + TEMPLATE_SCHEMA + ".%I INCLUDING ALL)', t, t); "
            + "END LOOP; END $$");
        registerTenant(schema);
    }

    /** Drops the tenant's schema and registry row. */
    public void dropTenant(String schema) {
        requireSafe(schema);
        TenantContext.clear();
        tenants.findBySchemaName(schema).ifPresent(tenants::delete);
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private void registerTenant(String schema) {
        if (tenants.findBySchemaName(schema).isEmpty()) {
            Tenant tenant = new Tenant(schema, TenantPlan.FREE, schema, "agentic-ai", AccountType.B2B, ExperienceMode.SIMPLE);
            tenant.markActive();
            tenants.save(tenant);
        }
    }

    /** A real agent row in {@code schema}. */
    public UUID newAgent(String schema, String name, boolean shareMemoryWithTenant) {
        TenantContext.set(TenantContext.of(schema, "memeval-setup", Set.of(ROLE_TENANT_ADMIN)));
        try {
            return tx.execute(status -> {
                Agent agent = new Agent(name, "You are helpful.", ProviderName.OPENAI, "gpt-4o-mini", 1024, 0.2, 1.0, true,
                    ExperienceMode.SIMPLE);
                agent.setShareMemoryWithTenant(shareMemoryWithTenant);
                return agents.save(agent).getId();
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** Sets a boolean behaviour setting for the whole tenant through the real settings service. */
    public void setTenantBoolean(String schema, String key, boolean value) {
        TenantContext.set(TenantContext.of(schema, "memeval-setup", Set.of(ROLE_TENANT_ADMIN)));
        try {
            settings.set(SettingScope.TENANT, null, key, value, "memory eval arm switch");
        } finally {
            TenantContext.clear();
        }
    }

    private static void requireSafe(String schema) {
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("unsafe schema name " + schema);
        }
    }
}
