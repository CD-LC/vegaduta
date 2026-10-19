package com.agenticai.core.tenancy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * The startup Flyway run ({@code spring.flyway}, which also applies the tenant scripts to the
 * default schema) with the one Apache AGE exception {@link TenantSchemaMigrator} makes for tenant
 * schemas: when the database server does not offer the {@code age} extension, V44 (graph memory) is
 * recorded as applied without running and the migration continues. Every other failure is rethrown
 * unchanged, so this behaves exactly like Spring Boot's default ({@code flyway.migrate()}) on a
 * server that has AGE. Recording V44 is idempotent and a replica that loses the race to record it
 * simply retries (the catch block is outside Flyway's advisory lock).
 */
@Component
public class AgeTolerantFlywayMigrationStrategy implements FlywayMigrationStrategy {

    private static final int MAX_ATTEMPTS = 3;

    private final TenantSchemaMigrator tenantSchemaMigrator;

    public AgeTolerantFlywayMigrationStrategy(TenantSchemaMigrator tenantSchemaMigrator) {
        this.tenantSchemaMigrator = tenantSchemaMigrator;
    }

    @Override
    public void migrate(Flyway flyway) {
        for (int attempt = 0; ; attempt++) {
            try {
                flyway.migrate();
                return;
            } catch (FlywayException e) {
                MigrationInfo[] pending = flyway.info().pending();
                if (attempt + 1 < MAX_ATTEMPTS
                        && pending.length > 0
                        && tenantSchemaMigrator.isAgeUnavailableFailure(e, pending[0])) {
                    tenantSchemaMigrator.recordGraphMemorySkipped(
                            defaultSchema(flyway), pending[0]);
                    continue;
                }
                // Concurrent replicas: Flyway's advisory lock serialises migrate() but this catch
                // runs outside it, so a second instance can find V44 already recorded by the first.
                if (attempt + 1 < MAX_ATTEMPTS
                        && TenantSchemaMigrator.isGraphMemorySkippedByAnotherInstance(
                                e, flyway.info().all())) {
                    continue;
                }
                throw e;
            }
        }
    }

    static String defaultSchema(Flyway flyway) {
        String schema = flyway.getConfiguration().getDefaultSchema();
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        String[] schemas = flyway.getConfiguration().getSchemas();
        return schemas != null && schemas.length > 0 ? schemas[0] : "public";
    }
}
