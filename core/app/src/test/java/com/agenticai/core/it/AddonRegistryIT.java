package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.providers.addons.AddonApi;
import com.agenticai.core.providers.addons.AddonService;
import com.agenticai.core.providers.addons.AddonState;
import com.agenticai.core.providers.addons.AddonStateRepository;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformScope;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The add-on registry against the real application context and a real Postgres: the V656 table
 * matches the entity (Hibernate validates the schema at boot), a person's request and the
 * installer's report both round-trip through the real beans, the host calls need no tenant, the
 * audit entry lands in the caller's tenant schema, and two writers racing on one row cannot both win.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class AddonRegistryIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_addons";

    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMigrationService tenantMigrationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AddonService service;
    @Autowired private AddonStateRepository repository;

    @BeforeEach
    void tenant() {
        if (tenantRepository.findAll().stream().noneMatch(t -> SCHEMA.equals(t.getSchemaName()))) {
            Tenant tenant = new Tenant(SCHEMA, TenantPlan.FREE, SCHEMA, "agentic-ai", AccountType.B2B, ExperienceMode.SIMPLE);
            tenant.markActive();
            tenantRepository.save(tenant);
            assertThat(tenantMigrationService.migrateAllActiveTenants()).isEmpty();
        }
        jdbc.update("DELETE FROM control_plane.addon_state");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM control_plane.addon_state");
    }

    private long auditEntries(String action, String target) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".audit_log_entry WHERE action_type = ? AND target_id = ?",
                Long.class, action, target);
    }

    private static void asOwner() {
        TenantContext.set(TenantContext.of(SCHEMA, "it-owner", Set.of("ROLE_tenant-admin")));
    }

    @Test
    void aRequestAndTheInstallersReportsRoundTripThroughRealBeansAndTheDatabase() {
        asOwner();
        AddonApi.Overview first = service.overview(true);
        assertThat(first.addons()).extracting(AddonApi.View::id).contains("workspace", "search", "documents", "browser", "tools");
        assertThat(first.addons()).filteredOn(v -> v.id().equals("tools")).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo("off"));
        assertThat(first.hostBridge().configured()).isFalse(); // no app.addons.host-token in this context

        long auditedBefore = auditEntries("addon.enable", "browser");
        AddonApi.ChangeResult enabled = service.enable("browser", "it-owner");
        assertThat(enabled.changed()).containsExactly("browser");
        assertThat(enabled.addon().status()).isEqualTo("enable_requested");
        // full tier renders the single-box --browser flag as the browser compose profile
        assertThat(enabled.apply().profiles()).contains("browser");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM control_plane.addon_state WHERE addon_id = 'browser'");
        assertThat(row).containsEntry("desired", true).containsEntry("rev", 1L).containsEntry("desired_by", "it-owner");
        // the audit log is append-only: count entries instead of clearing them
        assertThat(auditEntries("addon.enable", "browser")).isEqualTo(auditedBefore + 1);

        TenantContext.clear();
        AddonApi.HostWanted wanted = PlatformScope.call(service::hostWanted);
        assertThat(wanted.addons()).filteredOn(i -> i.id().equals("browser")).singleElement()
                .satisfies(i -> {
                    assertThat(i.wanted()).isTrue();
                    assertThat(i.rev()).isEqualTo(1L);
                });
        AddonApi.ReportResult accepted = service.hostReport(new AddonApi.ReportRequest(
                List.of(new AddonApi.ReportItem("browser", 1L, "running", "healthy"))));
        assertThat(accepted.accepted()).isEqualTo(1);

        asOwner();
        AddonApi.View on = service.get("browser");
        assertThat(on.status()).isEqualTo("on");
        assertThat(on.reportedAt()).isNotNull();

        AddonApi.ChangeResult disabled = service.disable("browser", false, "it-owner");
        assertThat(disabled.addon().status()).isEqualTo("disable_requested");
        assertThat(jdbc.queryForObject("SELECT rev FROM control_plane.addon_state WHERE addon_id = 'browser'", Long.class)).isEqualTo(2L);
        TenantContext.clear();
        service.hostReport(new AddonApi.ReportRequest(List.of(new AddonApi.ReportItem("browser", 2L, "stopped", null))));
        asOwner();
        assertThat(service.get("browser").status()).isEqualTo("off");
    }

    @Test
    void aFirstReportForAnUntouchedAddonCreatesItsRowWithoutChangingTheWish() {
        service.hostReport(new AddonApi.ReportRequest(List.of(new AddonApi.ReportItem("workspace", 0L, "running", null))));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM control_plane.addon_state WHERE addon_id = 'workspace'");
        // On the full tier 'workspace' is not applicable to the install, so the first host
        // report records it with desired=effectiveNow=false rather than inheriting the
        // single-box default-on wish.
        assertThat(row).containsEntry("desired", false).containsEntry("rev", 0L).containsEntry("reported_status", "running");
        assertThat(row.get("desired_by")).isNull();
    }

    @Test
    void aStaleCopyOfARowIsRefusedByTheVersionColumn() {
        asOwner();
        service.enable("search", "it-owner");
        AddonState stale = repository.findById("search").orElseThrow();
        service.disable("search", false, "it-owner"); // the winner: bumps rev and the lock version
        assertThatThrownBy(() -> repository.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void theDatabaseRefusesAReportStatusTheCodeDoesNotKnow() {
        jdbc.update("INSERT INTO control_plane.addon_state (addon_id, desired) VALUES ('search', true)");
        assertThatThrownBy(() -> jdbc.update("UPDATE control_plane.addon_state SET reported_status = 'exploded' WHERE addon_id = 'search'"))
                .hasMessageContaining("addon_state_reported_status_chk");
    }
}
