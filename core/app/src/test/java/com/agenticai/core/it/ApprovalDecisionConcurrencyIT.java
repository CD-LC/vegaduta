package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestAlreadyDecidedException;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two people deciding the same approval at once, on real Postgres. The service's own PENDING check
 * runs outside a transaction, so on its own it cannot stop both deciders passing it; the row lock
 * taken in {@code ApprovalRequestRecorder} is what does.
 *
 * <p>The first test is deterministic: a stand-in "first decider" holds the row lock and then marks
 * it approved, while two real deciders (who both already read PENDING) are made to wait on that
 * lock. Without the lock they do not wait, both read PENDING and both succeed, so the test fails.
 * The second is a plain many-thread race for the "exactly once" outcome.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ApprovalDecisionConcurrencyIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_approvals";

    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMigrationService tenantMigrationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private HumanApprovalGateService gate;

    private UUID requestId;

    @BeforeEach
    void tenantAndPendingRequest() {
        if (tenantRepository.findAll().stream().noneMatch(t -> SCHEMA.equals(t.getSchemaName()))) {
            Tenant tenant = new Tenant(SCHEMA, TenantPlan.FREE, SCHEMA, "agentic-ai", AccountType.B2B, ExperienceMode.SIMPLE);
            tenant.markActive();
            tenantRepository.save(tenant);
            assertThat(tenantMigrationService.migrateAllActiveTenants()).isEmpty();
        }
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
        try {
            // No executor is registered for this type, so a successful approve only records and audits.
            requestId = requests.save(new ApprovalRequest("it.no_executor", "decide me", "{}", "it-user")).getId();
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void deciderWaitingOnTheRowLockIsRefusedOnceTheFirstDecisionCommits() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // The "first decider": locks the row, then marks it approved and commits on release.
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT 1 FROM " + SCHEMA + ".approval_request WHERE id = ? FOR UPDATE", requestId);
            held.countDown();
            await(release);
            jdbc.update("UPDATE " + SCHEMA + ".approval_request SET status = 'APPROVED', decided_by = 'first-admin' WHERE id = ?", requestId);
        }));
        assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

        // Two real deciders, both reading PENDING before they reach the lock.
        List<CompletableFuture<String>> deciders = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String admin = "second-admin-" + i;
            deciders.add(CompletableFuture.supplyAsync(() -> decide(admin)));
        }

        boolean bothBlocked = waitForLockWaiters(2);
        release.countDown();
        first.get(30, TimeUnit.SECONDS);

        for (CompletableFuture<String> decider : deciders) {
            assertThat(decider.get(30, TimeUnit.SECONDS)).as("a decider that lost the race must be refused").isEqualTo("ALREADY_DECIDED");
        }
        assertThat(bothBlocked).as("both deciders waited on the request's row lock").isTrue();
        assertThat(jdbc.queryForObject("SELECT decided_by FROM " + SCHEMA + ".approval_request WHERE id = ?", String.class, requestId))
            .isEqualTo("first-admin");
    }

    @Test
    void manyDecidersRacingApproveExactlyOnce() throws Exception {
        int deciders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(deciders);
        CyclicBarrier start = new CyclicBarrier(deciders);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < deciders; i++) {
                String admin = "admin-" + i;
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    String outcome = decide(admin);
                    (outcome.equals("APPROVED") ? approved : refused).incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(approved.get()).as("exactly one decider wins").isEqualTo(1);
        assertThat(refused.get()).isEqualTo(deciders - 1);
    }

    private String decide(String admin) {
        TenantContext.set(TenantContext.of(SCHEMA, admin, Set.of()));
        try {
            gate.approve(requestId, admin);
            return "APPROVED";
        } catch (ApprovalRequestAlreadyDecidedException e) {
            return "ALREADY_DECIDED";
        } finally {
            TenantContext.clear();
        }
    }

    /** True once {@code expected} backends of this database wait on a lock; false after a few seconds (no lock taken). */
    private boolean waitForLockWaiters(int expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= expected) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
