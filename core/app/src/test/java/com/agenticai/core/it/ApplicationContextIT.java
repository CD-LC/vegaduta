package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * "Boot the jar" as a test: the full application context starts against a real pgvector+AGE
 * Postgres (Flyway runs every migration at startup, JPA validates every entity against the
 * resulting schema with {@code ddl-auto=validate}, every bean wires) and a Redis-protocol server,
 * then the readiness probe the orchestrator uses reports UP.
 *
 * <p>Profiles: {@code security,knowledge,graph-memory} - the set every compose/helm deployment
 * runs, minus the sidecar-free extras (mcpserver, workflow/temporal). No Keycloak is needed: the
 * JWT decoder resolves the issuer lazily, on the first authenticated request, and the health probes
 * are anonymous. {@code insecure} is deliberately NOT used: on its own it does not boot
 * (unconditional beans such as PlatformTenantDeletionOrchestrationService and FaceEnrollmentService
 * need {@code security}/{@code knowledge}-profiled beans), and combined with {@code security}
 * NoSecurityConfig refuses to start by design. Unlike CoreApplicationTests this never skips for
 * want of a local database and never touches one on localhost:5432.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
// Boot 4 no longer registers a TestRestTemplate bean on its own; this opts the test in.
@AutoConfigureTestRestTemplate
class ApplicationContextIT extends AbstractContextIT {

    @Autowired private TestRestTemplate http;

    @Autowired private Flyway flyway;

    @Autowired private TenantRepository tenantRepository;

    @Autowired private TenantMigrationService tenantMigrationService;

    @Autowired private JdbcTemplate jdbc;

    @Test
    void readinessProbeReportsUp() throws InterruptedException {
        // The tenant-migration gate opens once the boot runner has visited
        // every ACTIVE tenant; poll briefly rather than assume it already has.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        ResponseEntity<Map> response;
        do {
            response = http.getForEntity("/actuator/health/readiness", Map.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                break;
            }
            Thread.sleep(1000);
        } while (Instant.now().isBefore(deadline));

        assertThat(response.getStatusCode())
                .as("readiness body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");

        ResponseEntity<Map> liveness = http.getForEntity("/actuator/health/liveness", Map.class);
        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bootFlywayLeftNothingPending() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isNotEmpty();
    }

    /** Signup's persistence + schema step through the real JPA and Flyway wiring. */
    @Test
    void newActiveTenantGetsAMigratedSchema() {
        String schema = "tenant_it_context";
        Tenant tenant =
                new Tenant(
                        schema,
                        TenantPlan.FREE,
                        schema,
                        "agentic-ai",
                        AccountType.B2B,
                        ExperienceMode.SIMPLE);
        tenant.markActive();
        tenantRepository.save(tenant);

        assertThat(tenantMigrationService.migrateAllActiveTenants())
                .as("schemas that failed")
                .isEmpty();

        Integer tables =
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'knowledge_chunks'",
                        Integer.class,
                        schema);
        assertThat(tables).isEqualTo(1);
    }
}
