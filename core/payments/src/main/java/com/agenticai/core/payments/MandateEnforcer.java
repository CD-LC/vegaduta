package com.agenticai.core.payments;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The §E1 gate: every payment tool call passes through this before money can move. Checks, in order
 * (each failure is a {@link MandateViolationException} with a stable code):
 *
 * <ol>
 *   <li><b>Revocation tombstone</b> - Redis {@code mandate:revoked:<id>}, so a revoked mandate
 *       kills in-flight calls within the same second.
 *   <li><b>Ed25519 signature</b> - verifies the stored {@code canonical_json} verbatim against the
 *       mandate's signing key. A row whose caps/VPA fields were tampered post-sign fails here
 *       because the signed document is the source of truth.
 *   <li><b>Status</b> - must be ACTIVE (REVOKED/EXPIRED/SUSPENDED fail).
 *   <li><b>Validity window</b> - {@code valid_from <= now < valid_until}.
 *   <li><b>Scope</b> - COLLECT/BOTH for collection types, PAY/BOTH for payouts.
 *   <li><b>Per-transaction cap</b> - amount <= {@code max_per_txn_paise}.
 *   <li><b>VPA allow-list</b> - NULL/empty list = unrestricted; otherwise the payee VPA must appear
 *       (VPA check is skipped when the type has no VPA, e.g. a link awaiting the payer's choice).
 *   <li><b>Merchant-category allow-list</b> - same shape as VPAs.
 *   <li><b>Daily/monthly caps via ledger</b> - {@code SELECT ... FOR UPDATE} on the {@code
 *       payment_mandate} row itself, then a read of the mandate's latest {@code mandate_ledger} row
 *       and a new CHARGE row carrying the new running totals. The mandate-row lock makes the
 *       check+insert atomic per mandate: concurrent calls (including two FIRST charges, when no
 *       ledger row exists yet) serialize on it instead of both passing a cap they together exceed.
 *       Periods roll over by day/month-key change, not by summing history - the running total IS
 *       the ledger.
 * </ol>
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class MandateEnforcer {

    private static final Logger log = LoggerFactory.getLogger(MandateEnforcer.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final MandateSigningService signingService;
    private final MandateRevocationCache revocationCache;
    private final MandateLedgerRepository ledgerRepository;
    private final PaymentMandateRepository mandateRepository;

    public MandateEnforcer(
            MandateSigningService signingService,
            MandateRevocationCache revocationCache,
            MandateLedgerRepository ledgerRepository,
            PaymentMandateRepository mandateRepository) {
        this.signingService = signingService;
        this.revocationCache = revocationCache;
        this.ledgerRepository = ledgerRepository;
        this.mandateRepository = mandateRepository;
    }

    /**
     * Cheap checks that do not need a lock: tombstone, signature, status, validity window. Called
     * both at tool-call time and again right before gateway execution (a mandate may have been
     * revoked while approval was pending).
     */
    public void assertMandateUsable(PaymentMandate mandate, Instant now) {
        if (revocationCache.isRevoked(mandate.getId())
                || mandate.getStatus() == MandateStatus.REVOKED) {
            throw new MandateViolationException("MANDATE_REVOKED", "Mandate has been revoked");
        }
        if (mandate.getStatus() == MandateStatus.EXPIRED) {
            throw new MandateViolationException("MANDATE_EXPIRED", "Mandate has expired");
        }
        if (mandate.getStatus() == MandateStatus.SUSPENDED) {
            throw new MandateViolationException("MANDATE_SUSPENDED", "Mandate is suspended");
        }
        if (mandate.getStatus() != MandateStatus.ACTIVE) {
            throw new MandateViolationException("MANDATE_INACTIVE", "Mandate is not active");
        }
        if (!signingService.verify(mandate)) {
            throw new MandateViolationException(
                    "MANDATE_SIGNATURE_INVALID", "Mandate signature verification failed");
        }
        if (now.isBefore(mandate.getValidFrom())) {
            throw new MandateViolationException(
                    "MANDATE_NOT_YET_VALID",
                    "Mandate validity starts at " + mandate.getValidFrom());
        }
        if (!now.isBefore(mandate.getValidUntil())) {
            throw new MandateViolationException(
                    "MANDATE_EXPIRED", "Mandate validity ended at " + mandate.getValidUntil());
        }
    }

    /** Scope + per-transaction cap + VPA allow-list + merchant-category allow-list. */
    public void assertTransactionShape(
            PaymentMandate mandate,
            PaymentTransactionType type,
            long amountPaise,
            String vpa,
            String merchantCategory) {
        boolean collection =
                type == PaymentTransactionType.PAYMENT_LINK
                        || type == PaymentTransactionType.UPI_COLLECT;
        if (collection && !mandate.getScope().allowsCollection()) {
            throw new MandateViolationException(
                    "MANDATE_SCOPE",
                    "Mandate scope " + mandate.getScope() + " does not allow collections");
        }
        if (!collection && !mandate.getScope().allowsPayout()) {
            throw new MandateViolationException(
                    "MANDATE_SCOPE",
                    "Mandate scope " + mandate.getScope() + " does not allow payouts");
        }
        if (amountPaise <= 0) {
            throw new MandateViolationException("AMOUNT_INVALID", "Amount must be positive");
        }
        if (amountPaise > mandate.getMaxPerTxnPaise()) {
            throw new MandateViolationException(
                    "CAP_PER_TXN",
                    "Amount "
                            + amountPaise
                            + " paise exceeds per-transaction cap "
                            + mandate.getMaxPerTxnPaise());
        }
        List<String> allowedVpas = parseList(mandate.getAllowedVpas());
        if (!allowedVpas.isEmpty() && vpa != null && !allowedVpas.contains(vpa)) {
            throw new MandateViolationException(
                    "VPA_NOT_ALLOWED", "VPA is not on the mandate allow-list");
        }
        // A PAYMENT_LINK has no payee VPA at creation (payer chooses on the
        // link), so a VPA-restricted mandate can still create links - but a
        // UPI_COLLECT names its VPA and must be on-list.
        if (!allowedVpas.isEmpty()
                && type == PaymentTransactionType.UPI_COLLECT
                && (vpa == null || vpa.isBlank())) {
            throw new MandateViolationException(
                    "VPA_REQUIRED", "This mandate requires the payee VPA to be on its allow-list");
        }
        List<String> allowedCategories = parseList(mandate.getAllowedMerchantCategories());
        if (!allowedCategories.isEmpty() && !allowedCategories.contains(merchantCategory)) {
            throw new MandateViolationException(
                    "CATEGORY_NOT_ALLOWED", "Merchant category is not on the mandate allow-list");
        }
    }

    /**
     * Cap enforcement with mandate-row locking (§E1). Takes {@code SELECT ... FOR UPDATE} on the
     * {@code payment_mandate} row, reads the mandate's latest ledger row, compares the CHARGE's
     * running totals against the daily/monthly caps, then appends the CHARGE. Concurrent
     * transactions for one mandate serialize on this lock.
     *
     * <p>Must run inside the caller's transaction (the lock only lives as long as it does).
     * Day/month roll over naturally: a new {@code day_key} resets the carried daily total to this
     * charge.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveCaps(
            PaymentMandate mandate, UUID transactionId, long amountPaise, Instant now) {
        java.time.LocalDate dayKey = now.atOffset(ZoneOffset.UTC).toLocalDate();
        String monthKey = dayKey.toString().substring(0, 7);

        // Serialize on the mandate row FIRST (JAVA-001): the ledger read below
        // then runs after any concurrent holder committed, so it sees that
        // holder's CHARGE row.
        if (mandateRepository.lockForCapUpdate(mandate.getId()).isEmpty()) {
            throw new MandateViolationException("MANDATE_NOT_FOUND", "Mandate no longer exists");
        }
        MandateLedgerEntry latest =
                ledgerRepository.findLatestForUpdate(mandate.getId()).orElse(null);
        long currentDaily =
                (latest != null && dayKey.equals(latest.getDayKey()))
                        ? latest.getDailySpendPaise()
                        : 0L;
        long currentMonthly =
                (latest != null && monthKey.equals(latest.getMonthKey()))
                        ? latest.getMonthlySpendPaise()
                        : 0L;

        if (currentDaily + amountPaise > mandate.getMaxDailyPaise()) {
            throw new MandateViolationException(
                    "CAP_DAILY",
                    "Daily cap "
                            + mandate.getMaxDailyPaise()
                            + " paise exceeded (used "
                            + currentDaily
                            + ")");
        }
        if (currentMonthly + amountPaise > mandate.getMaxMonthlyPaise()) {
            throw new MandateViolationException(
                    "CAP_MONTHLY",
                    "Monthly cap "
                            + mandate.getMaxMonthlyPaise()
                            + " paise exceeded (used "
                            + currentMonthly
                            + ")");
        }

        ledgerRepository.save(
                new MandateLedgerEntry(
                        mandate.getId(),
                        transactionId,
                        MandateLedgerEntry.KIND_CHARGE,
                        amountPaise,
                        dayKey,
                        currentDaily + amountPaise,
                        monthKey,
                        currentMonthly + amountPaise));
    }

    /**
     * Releases a cap reservation: appends a REVERSAL carrying decremented running totals.
     * Idempotent via {@code uq_mandate_ledger_reversal} - a duplicate (rejected-approval +
     * poller-expiry racing) hits the unique index; we swallow it since the reservation is already
     * released either way. Runs under the same {@code FOR UPDATE} discipline so a REVERSAL can
     * never interleave between another CHARGE's check and insert.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseCaps(UUID mandateId, UUID transactionId, Instant now) {
        java.time.LocalDate dayKey = now.atOffset(ZoneOffset.UTC).toLocalDate();
        String monthKey = dayKey.toString().substring(0, 7);

        // Lock the mandate row so this reversal serializes with any in-flight
        // CHARGE; the reversed amount comes from THIS transaction's own
        // CHARGE entry - never from whatever row happens to be latest.
        mandateRepository.lockForCapUpdate(mandateId);
        MandateLedgerEntry latest = ledgerRepository.findLatestForUpdate(mandateId).orElse(null);
        MandateLedgerEntry charge =
                ledgerRepository
                        .findByTransactionIdAndEntryKind(
                                transactionId, MandateLedgerEntry.KIND_CHARGE)
                        .orElse(null);
        if (latest == null || charge == null) {
            return;
        }
        long amount = charge.getAmountPaise();
        long newDaily =
                dayKey.equals(latest.getDayKey())
                        ? Math.max(0L, latest.getDailySpendPaise() - amount)
                        : 0L;
        long newMonthly =
                monthKey.equals(latest.getMonthKey())
                        ? Math.max(0L, latest.getMonthlySpendPaise() - amount)
                        : 0L;
        try {
            // Positive amount - entry_kind carries direction (the schema
            // CHECK is amount_paise >= 0).
            ledgerRepository.saveAndFlush(
                    new MandateLedgerEntry(
                            mandateId,
                            transactionId,
                            MandateLedgerEntry.KIND_REVERSAL,
                            amount,
                            dayKey,
                            newDaily,
                            monthKey,
                            newMonthly));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.debug("Ledger reversal for transaction {} already recorded", transactionId);
        }
    }

    private static List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = MAPPER.readValue(json, STRING_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
