package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import com.agenticai.core.tenancy.TenantStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every Flyway migration applies cleanly to an EMPTY pgvector+AGE database,
 * exactly as core does at boot, and then per tenant schema - the failure
 * class (duplicate versions, SQL that only breaks on a fresh database, an
 * extension missing from the image, tenant DDL that assumes public) that unit
 * tests cannot see and that has shipped broken core images before.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationsIT {

    private static final String STRICT_SCHEMA = "tenant_it_strict";
    private static final List<String> SERVICE_SCHEMAS = List.of("tenant_it_alpha", "tenant_it_beta");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createEmptyDatabase() {
        dataSource = IntegrationTestContainers.freshDatabase("migrations_it");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    @Order(1)
    void bootMigrationsApplyFromEmpty() {
        MigrateResult result = PlatformMigrations.migrateBoot(dataSource);

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);

        Flyway flyway = PlatformMigrations.bootFlyway(dataSource);
        assertThat(flyway.info().pending()).as("pending after migrate").isEmpty();
        assertThat(Arrays.stream(flyway.info().all()).filter(i -> i.getState() == MigrationState.FAILED))
            .as("failed migrations").isEmpty();
        Integer applied = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
        assertThat(applied).isEqualTo(result.migrationsExecuted);

        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class))
            .as("extensions the image must provide").contains("vector", "age");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'control_plane' AND table_name = 'tenant'",
            Integer.class)).isEqualTo(1);
    }

    /** A brand-new tenant schema with plain Flyway: no already-exists repair can mask a broken script. */
    @Test
    @Order(2)
    void tenantMigrationsApplyToFreshSchemaWithoutRepair() {
        MigrateResult result = PlatformMigrations.strictTenantFlyway(dataSource, STRICT_SCHEMA).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
        assertThat(PlatformMigrations.strictTenantFlyway(dataSource, STRICT_SCHEMA).info().pending()).isEmpty();
        assertThat(tableExists(STRICT_SCHEMA, "knowledge_chunks")).isTrue();
        assertThat(tableExists(STRICT_SCHEMA, "agent")).isTrue();
    }

    /**
     * Two more tenant schemas through the platform's own service
     * (TenantMigrationService -> TenantSchemaMigrator), the code the boot
     * runner and the migrate-only Job both use. Each must end with exactly
     * the version set the strict run applied, and a second pass is a no-op.
     */
    @Test
    @Order(3)
    void tenantMigrationServiceProvisionsAdditionalSchemas() {
        TenantRepository tenants = mock(TenantRepository.class);
        List<Tenant> active = SERVICE_SCHEMAS.stream().map(MigrationsIT::activeTenant).toList();
        when(tenants.findByStatus(TenantStatus.ACTIVE)).thenReturn(active);
        TenantMigrationService service =
            new TenantMigrationService(tenants, new TenantSchemaMigrator(dataSource), 2);

        assertThat(service.migrateAllActiveTenants()).as("schemas that failed").isEmpty();

        Set<String> expected = appliedVersions(STRICT_SCHEMA);
        assertThat(expected).isNotEmpty();
        for (String schema : SERVICE_SCHEMAS) {
            assertThat(appliedVersions(schema)).as("versions in " + schema).isEqualTo(expected);
            assertThat(tableExists(schema, "knowledge_chunks")).isTrue();
        }

        assertThat(service.migrateAllActiveTenants()).as("idempotent re-run").isEmpty();
        for (String schema : SERVICE_SCHEMAS) {
            assertThat(appliedVersions(schema)).isEqualTo(expected);
        }
    }

    private static Tenant activeTenant(String schema) {
        Tenant tenant = new Tenant(schema, TenantPlan.FREE, schema, "agentic-ai", AccountType.B2B, ExperienceMode.SIMPLE);
        tenant.markActive();
        return tenant;
    }

    private static Set<String> appliedVersions(String schema) {
        return Arrays.stream(PlatformMigrations.strictTenantFlyway(dataSource, schema).info().applied())
            .filter(MigrationInfo::isVersioned)
            .map(i -> i.getVersion().getVersion())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean tableExists(String schema, String table) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
            Integer.class, schema, table);
        return n != null && n == 1;
    }
}
