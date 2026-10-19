package com.agenticai.core.it;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The boot-time Flyway run, reproduced without Spring: the same settings as
 * {@code spring.flyway.*} in app-config's application.yml (both locations in
 * one run - hence one shared version namespace - out-of-order on, no
 * baseline). Keep in step with that file if it changes.
 */
final class PlatformMigrations {

    static final String CONTROLPLANE = "classpath:db/migration/controlplane";
    static final String TENANT = "classpath:db/migration/tenant";

    private PlatformMigrations() {
    }

    static Flyway bootFlyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(CONTROLPLANE, TENANT)
            .outOfOrder(true)
            .baselineOnMigrate(false)
            .load();
    }

    /** A tenant schema migrated with NO repair logic, unlike TenantSchemaMigrator. */
    static Flyway strictTenantFlyway(DataSource dataSource, String schema) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(TENANT)
            .schemas(schema)
            .createSchemas(true)
            .outOfOrder(true)
            .baselineOnMigrate(false)
            .load();
    }

    static MigrateResult migrateBoot(DataSource dataSource) {
        return bootFlyway(dataSource).migrate();
    }
}
