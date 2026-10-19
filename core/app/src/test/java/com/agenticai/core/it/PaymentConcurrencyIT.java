package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.payments.MandateEnforcer;
import com.agenticai.core.payments.MandateViolationException;
import com.agenticai.core.payments.PaymentExecutionService;
import com.agenticai.core.payments.PaymentMandate;
import com.agenticai.core.payments.PaymentMandateRepository;
import com.agenticai.core.payments.PaymentTransactionRepository;
import com.agenticai.core.payments.PaymentTransactionStatus;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
 * JAVA-001/002 proven against real Postgres row locks, deterministically: a
 * first transaction does its work and then HOLDS its locks (does not commit)
 * until the test sees the second transaction blocked on a lock in
 * pg_stat_activity - or a short timeout passes, which is exactly what happens
 * when the lock is missing and the race is open. Only then does the first
 * commit, and the outcome is asserted.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class PaymentConcurrencyIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_payments";

    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMigrationService tenantMigrationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MandateEnforcer enforcer;
    @Autowired private PaymentMandateRepository mandates;
    @Autowired private PaymentTransactionRepository transactions;
    @Autowired private PaymentExecutionService execution;

    private UUID mandateId;
    private UUID agentId;

    @BeforeEach
    void tenantAndMandate() {
        if (tenantRepository.findAll().stream().noneMatch(t -> SCHEMA.equals(t.getSchemaName()))) {
            Tenant tenant = new Tenant(SCHEMA, TenantPlan.FREE, SCHEMA, "agentic-ai", AccountType.B2B, ExperienceMode.SIMPLE);
            tenant.markActive();
            tenantRepository.save(tenant);
            assertThat(tenantMigrationService.migrateAllActiveTenants()).isEmpty();
        }
        agentId = UUID.randomUUID();
        mandateId = UUID.randomUUID();
        String keyId = "psk_it_" + mandateId;
        jdbc.update("INSERT INTO " + SCHEMA + ".agent (id, name, system_prompt, provider_name, model, max_tokens, "
            + "temperature, knowledge_base_enabled, created_at, updated_at) "
            + "VALUES (?, 'it', 'x', 'OPENAI', 'gpt-4o-mini', 256, 0.2, false, now(), now())", agentId);
        jdbc.update("INSERT INTO " + SCHEMA + ".payment_signing_key (key_id, public_key, private_key_encrypted, status) "
            + "VALUES (?, '\\x00'::bytea, 'x', 'RETIRED')", keyId);
        // Daily cap 100 000: one 60 000 charge fits, two do not.
        jdbc.update("INSERT INTO " + SCHEMA + ".payment_mandate (id, agent_id, granted_by_user_id, scope, "
            + "max_per_txn_paise, max_daily_paise, max_monthly_paise, approval_policy, valid_from, valid_until, "
            + "status, canonical_json, signature, key_id) VALUES (?, ?, 'it-user', 'COLLECT', 100000, 100000, "
            + "10000000, 'NEVER', now() - interval '1 hour', now() + interval '1 day', 'ACTIVE', '{}', "
            + "'\\x00'::bytea, ?)", mandateId, agentId, keyId);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** JAVA-001: two concurrent FIRST charges (no ledger row yet) must not both pass the daily cap. */
    @Test
    void concurrentFirstChargesCannotBothPassTheDailyCap() throws Exception {
        UUID txnA = insertTransaction(60_000);
        UUID txnB = insertTransaction(60_000);
        CountDownLatch aReserved = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> inTenantTx(() -> {
            enforcer.reserveCaps(mandate(), txnA, 60_000, Instant.now());
            aReserved.countDown();
            await(releaseA);
            return "OK";
        }));
        assertThat(aReserved.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
            try {
                return inTenantTx(() -> {
                    enforcer.reserveCaps(mandate(), txnB, 60_000, Instant.now());
                    return "OK";
                });
            } catch (MandateViolationException e) {
                return e.getCode();
            }
        });

        boolean blocked = waitForLockWaiter();
        releaseA.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(second.get(30, TimeUnit.SECONDS)).as("second charge must see the first").isEqualTo("CAP_DAILY");
        assertThat(blocked).as("second charge waited on the mandate row lock").isTrue();
        Long charged = jdbc.queryForObject("SELECT coalesce(sum(amount_paise), 0) FROM " + SCHEMA
            + ".mandate_ledger WHERE mandate_id = ? AND entry_kind = 'CHARGE'", Long.class, mandateId);
        assertThat(charged).isEqualTo(60_000L);
    }

    /** JAVA-002: the poller's expiry racing the webhook's capture must never overwrite CAPTURED or release caps. */
    @Test
    void expiryRacingCaptureNeverOverwritesCaptured() throws Exception {
        UUID txn = insertTransaction(10_000);
        jdbc.update("UPDATE " + SCHEMA + ".payment_transaction SET status = 'AWAITING_PAYMENT', "
            + "razorpay_entity_id = ? WHERE id = ?", "plink_" + txn, txn);
        inTenantTx(() -> {
            enforcer.reserveCaps(mandate(), txn, 10_000, Instant.now());
            return null;
        });
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);

        CompletableFuture<Boolean> webhook = CompletableFuture.supplyAsync(() -> inTenantTx(() -> {
            boolean changed = execution.settleCaptured(txn, "pay_it", 10_000L);
            captured.countDown();
            await(releaseCapture);
            return changed;
        }));
        assertThat(captured.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> poller = CompletableFuture.runAsync(() -> inTenantTx(() -> {
            execution.settleExpired(txn);
            return null;
        }));

        boolean blocked = waitForLockWaiter();
        releaseCapture.countDown();
        assertThat(webhook.get(30, TimeUnit.SECONDS)).isTrue();
        poller.get(30, TimeUnit.SECONDS);

        assertThat(blocked).as("expiry waited on the transaction row lock").isTrue();
        String status = jdbc.queryForObject("SELECT status FROM " + SCHEMA + ".payment_transaction WHERE id = ?",
            String.class, txn);
        assertThat(status).isEqualTo(PaymentTransactionStatus.CAPTURED.name());
        Integer reversals = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
            + ".mandate_ledger WHERE transaction_id = ? AND entry_kind = 'REVERSAL'", Integer.class, txn);
        assertThat(reversals).as("captured payment's cap reservation must not be released").isZero();
    }

    // ------------------------------------------------------------------

    private PaymentMandate mandate() {
        return mandates.findById(mandateId).orElseThrow();
    }

    private UUID insertTransaction(long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".payment_transaction (id, mandate_id, agent_id, type, amount_paise, "
            + "currency, gateway, status, idempotency_key, poll_count, created_at, updated_at) VALUES "
            + "(?, ?, ?, 'PAYMENT_LINK', ?, 'INR', 'RAZORPAY', 'INITIATED', ?, 0, now(), now())",
            id, mandateId, agentId, amount, "it-" + id);
        return id;
    }

    private <T> T inTenantTx(Supplier<T> work) {
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    /** True once some backend of this database waits on a lock; false after a few seconds (no lock = race open). */
    private boolean waitForLockWaiter() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
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
