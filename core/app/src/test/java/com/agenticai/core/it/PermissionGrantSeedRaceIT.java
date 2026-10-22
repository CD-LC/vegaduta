package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.admin.permission.DefaultPermissionBundles;
import com.agenticai.core.admin.permission.UserPermissionService;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A user's first requests (a page load fires several API calls at once) all find no permission rows
 * and all try to seed them, on real Postgres. The seed rows hit {@code uq_user_permission_grant};
 * with a JPA {@code save} the insert is deferred to commit, so the losers failed after the code's
 * own catch had returned (the converter then logged an ERROR and ran the request with roles only).
 * Every racer must now get the same, complete answer and the user must end with exactly one set of
 * rows.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class PermissionGrantSeedRaceIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_permgrants";

    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMigrationService tenantMigrationService;
    @Autowired private UserPermissionService permissions;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void tenant() {
        if (tenantRepository.findAll().stream().noneMatch(t -> SCHEMA.equals(t.getSchemaName()))) {
            Tenant tenant =
                    new Tenant(
                            SCHEMA,
                            TenantPlan.FREE,
                            SCHEMA,
                            "agentic-ai",
                            AccountType.B2B,
                            ExperienceMode.SIMPLE);
            tenant.markActive();
            tenantRepository.save(tenant);
            assertThat(tenantMigrationService.migrateAllActiveTenants()).isEmpty();
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void concurrentFirstRequestsAllGetTheSeededPermissionsAndNoneFails() throws Exception {
        Set<String> roles = Set.of("tenant-admin");
        Set<String> expected = DefaultPermissionBundles.unionForRoles(roles);
        assertThat(expected)
                .as("the bundle must be non-empty for the race to mean anything")
                .isNotEmpty();

        for (int round = 0; round < 5; round++) {
            String user = "it-first-signin-" + UUID.randomUUID();
            int racers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            CyclicBarrier start = new CyclicBarrier(racers);
            List<Future<Set<String>>> answers = new ArrayList<>();
            try {
                for (int i = 0; i < racers; i++) {
                    answers.add(
                            pool.submit(
                                    () -> {
                                        TenantContext.set(TenantContext.of(SCHEMA, user, Set.of()));
                                        try {
                                            start.await(30, TimeUnit.SECONDS);
                                            return permissions.getOrSeedEffectivePermissions(
                                                    SCHEMA, user, roles, user);
                                        } finally {
                                            TenantContext.clear();
                                        }
                                    }));
                }
                for (Future<Set<String>> answer : answers) {
                    assertThat(answer.get(60, TimeUnit.SECONDS)).isEqualTo(expected);
                }
            } finally {
                pool.shutdownNow();
            }

            Integer rows =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM control_plane.user_permission_grant WHERE keycloak_user_id = ?",
                            Integer.class,
                            user);
            assertThat(rows)
                    .as("the seed marker plus the bundle, once")
                    .isEqualTo(expected.size() + 1);
        }
    }
}
