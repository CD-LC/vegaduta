package com.agenticai.core.payments;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human-facing lifecycle of {@link PaymentMandate}s: preview (the grant
 * wizard's "review and sign" step - returns the canonical AP2-shaped JSON
 * and its SHA-256 hash BEFORE anything is persisted), create (canonicalize
 * -&gt; sign with the tenant's Ed25519 key -&gt; persist), list, and revoke
 * (immediate: DB row + Redis tombstone in the same call, so in-flight tool
 * calls die within the same second).
 *
 * <p>Mandates are deliberately immutable post-signing - there is no update
 * endpoint; a change is revoke + re-grant, which is what keeps the
 * signature honest. All mutating paths audit-log.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentMandateService {

    private static final Logger log = LoggerFactory.getLogger(PaymentMandateService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final PaymentMandateRepository mandateRepository;
    private final MandateSigningService signingService;
    private final MandateCanonicalizer canonicalizer;
    private final MandateRevocationCache revocationCache;
    private final MandateLedgerRepository ledgerRepository;
    private final AuditLogService auditLogService;

    public PaymentMandateService(
        PaymentMandateRepository mandateRepository,
        MandateSigningService signingService,
        MandateCanonicalizer canonicalizer,
        MandateRevocationCache revocationCache,
        MandateLedgerRepository ledgerRepository,
        AuditLogService auditLogService
    ) {
        this.mandateRepository = mandateRepository;
        this.signingService = signingService;
        this.canonicalizer = canonicalizer;
        this.revocationCache = revocationCache;
        this.ledgerRepository = ledgerRepository;
        this.auditLogService = auditLogService;
    }

    /** The canonical document + hash a wizard shows for review before granting - nothing persisted. */
    public MandatePreview preview(MandateDraft draft) {
        UUID previewId = UUID.randomUUID();
        String canonical = canonicalize(draft, previewId);
        return new MandatePreview(canonical, canonicalizer.canonicalHash(canonical));
    }

    /**
     * Grants a mandate: canonicalizes, signs, persists ACTIVE. The signed
     * {@code canonical_json} is stored verbatim - {@link MandateEnforcer}
     * verifies these exact bytes at execution time, so any later column
     * drift from the signed terms fails verification rather than silently
     * authorizing under changed caps.
     */
    @Transactional
    public PaymentMandate grant(MandateDraft draft) {
        validate(draft);
        PaymentSigningKey key = signingService.activeKey();
        UUID mandateId = UUID.randomUUID();
        String canonical = canonicalize(draft, mandateId);
        byte[] signature = signingService.sign(key, canonical);

        PaymentMandate mandate = new PaymentMandate();
        mandate.setId(mandateId);
        mandate.setAgentId(draft.agentId());
        mandate.setGrantedByUserId(draft.grantedByUserId());
        mandate.setScope(draft.scope());
        mandate.setMaxPerTxnPaise(draft.maxPerTxnPaise());
        mandate.setMaxDailyPaise(draft.maxDailyPaise());
        mandate.setMaxMonthlyPaise(draft.maxMonthlyPaise());
        mandate.setAllowedVpas(writeList(draft.allowedVpas()));
        mandate.setAllowedMerchantCategories(writeList(draft.allowedMerchantCategories()));
        mandate.setApprovalPolicy(draft.approvalPolicy());
        mandate.setApprovalThresholdPaise(draft.approvalThresholdPaise());
        mandate.setValidFrom(draft.validFrom());
        mandate.setValidUntil(draft.validUntil());
        mandate.setStatus(MandateStatus.ACTIVE);
        mandate.setCanonicalJson(canonical);
        mandate.setSignature(signature);
        mandate.setKeyId(key.getKeyId());
        PaymentMandate saved = mandateRepository.save(mandate);

        audit("payment.mandate.grant", saved.getId().toString(), AuditOutcome.SUCCESS,
            "scope=" + draft.scope() + " perTxn=" + draft.maxPerTxnPaise()
                + " daily=" + draft.maxDailyPaise() + " monthly=" + draft.maxMonthlyPaise()
                + " sha256=" + canonicalizer.canonicalHash(canonical));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PaymentMandate> listForAgent(UUID agentId) {
        return mandateRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    /** Tenant-schema-scoped single-row read - a mandate in another schema is simply absent. */
    @Transactional(readOnly = true)
    public PaymentMandate findById(UUID id) {
        return mandateRepository.findById(id).orElse(null);
    }

    /** The mandate a tool call authorizes under - newest ACTIVE for the agent. */
    @Transactional(readOnly = true)
    public PaymentMandate activeMandateFor(UUID agentId) {
        return mandateRepository
            .findFirstByAgentIdAndStatusOrderByCreatedAtDesc(agentId, MandateStatus.ACTIVE)
            .orElse(null);
    }

    /**
     * Immediate revocation: flips the row to REVOKED AND drops the Redis
     * tombstone in the same call - {@link MandateEnforcer} checks the
     * tombstone first, so an in-flight tool call fails within the same
     * second (§E1's revocation-latency requirement), with the DB row as the
     * authoritative backstop.
     */
    @Transactional
    public PaymentMandate revoke(UUID mandateId, String reason) {
        PaymentMandate mandate = mandateRepository.findById(mandateId).orElse(null);
        if (mandate == null) {
            return null;
        }
        if (mandate.getStatus() == MandateStatus.REVOKED) {
            return mandate; // idempotent - a repeated revoke is a no-op
        }
        String decidedBy = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
        mandate.markRevoked(decidedBy, reason, Instant.now());
        PaymentMandate saved = mandateRepository.save(mandate);
        revocationCache.tombstone(mandateId);
        audit("payment.mandate.revoke", mandateId.toString(), AuditOutcome.SUCCESS,
            "by=" + decidedBy + " reason=" + (reason == null ? "" : reason));
        return saved;
    }

    /** Current running totals for the spend-vs-cap meter - a plain read of the latest ledger row, never a recomputation. */
    @Transactional(readOnly = true)
    public MandateSpendView spend(UUID mandateId) {
        PaymentMandate mandate = mandateRepository.findById(mandateId).orElse(null);
        return mandate == null ? null : spendOf(mandate);
    }

    /**
     * Spend-vs-cap meters for every mandate of an agent in one call, replacing N per-mandate
     * {@code /mandates/{id}/spend} round trips. Same ordering as {@link #listForAgent}.
     */
    @Transactional(readOnly = true)
    public java.util.List<MandateSpend> spendForAgent(UUID agentId) {
        return mandateRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
            .map(m -> new MandateSpend(m.getId(), spendOf(m)))
            .toList();
    }

    private MandateSpendView spendOf(PaymentMandate mandate) {
        MandateLedgerEntry latest = ledgerRepository.findFirstByMandateIdOrderByIdDesc(mandate.getId()).orElse(null);
        Instant now = Instant.now();
        java.time.LocalDate today = now.atOffset(java.time.ZoneOffset.UTC).toLocalDate();
        String thisMonth = today.toString().substring(0, 7);
        long daily = (latest != null && today.equals(latest.getDayKey())) ? latest.getDailySpendPaise() : 0L;
        long monthly = (latest != null && thisMonth.equals(latest.getMonthKey())) ? latest.getMonthlySpendPaise() : 0L;
        return new MandateSpendView(
            mandate.getMaxPerTxnPaise(), mandate.getMaxDailyPaise(), mandate.getMaxMonthlyPaise(),
            daily, monthly);
    }

    private String canonicalize(MandateDraft draft, UUID mandateId) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("unknown");
        return canonicalizer.canonicalize(
            mandateId, tenantId, draft.agentId(), draft.grantedByUserId(), draft.scope(),
            draft.maxPerTxnPaise(), draft.maxDailyPaise(), draft.maxMonthlyPaise(),
            draft.allowedVpas(), draft.allowedMerchantCategories(),
            draft.approvalPolicy(), draft.approvalThresholdPaise(),
            draft.validFrom(), draft.validUntil());
    }

    private static void validate(MandateDraft draft) {
        if (draft.agentId() == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (draft.grantedByUserId() == null || draft.grantedByUserId().isBlank()) {
            throw new IllegalArgumentException("grantedByUserId is required - a mandate must name its human grantor");
        }
        if (draft.scope() == null) {
            throw new IllegalArgumentException("scope is required");
        }
        if (draft.maxPerTxnPaise() <= 0 || draft.maxDailyPaise() <= 0 || draft.maxMonthlyPaise() <= 0) {
            throw new IllegalArgumentException("caps must be positive paise amounts");
        }
        if (draft.maxPerTxnPaise() > draft.maxDailyPaise() || draft.maxDailyPaise() > draft.maxMonthlyPaise()) {
            throw new IllegalArgumentException("caps must satisfy perTransaction <= daily <= monthly");
        }
        if (draft.approvalPolicy() == null) {
            throw new IllegalArgumentException("approvalPolicy is required");
        }
        if (draft.approvalPolicy() == MandateApprovalPolicy.ABOVE_THRESHOLD
            && (draft.approvalThresholdPaise() == null || draft.approvalThresholdPaise() <= 0)) {
            throw new IllegalArgumentException("ABOVE_THRESHOLD requires a positive approvalThresholdPaise");
        }
        if (draft.validFrom() == null || draft.validUntil() == null || !draft.validUntil().isAfter(draft.validFrom())) {
            throw new IllegalArgumentException("a valid validity window is required (validUntil after validFrom)");
        }
    }

    private void audit(String actionType, String targetId, AuditOutcome outcome, String detail) {
        try {
            String actor = TenantContext.current().map(TenantContext::getSubject).orElse("system");
            auditLogService.record(actor, actionType, "payment_mandate", targetId, outcome, detail);
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} on {} - the action itself stands", actionType, targetId, e);
        }
    }

    static String writeList(List<String> values) {
        try {
            return values == null || values.isEmpty() ? null : MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> readList(String json) {
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

    public record MandateDraft(
        UUID agentId,
        String grantedByUserId,
        MandateScope scope,
        long maxPerTxnPaise,
        long maxDailyPaise,
        long maxMonthlyPaise,
        List<String> allowedVpas,
        List<String> allowedMerchantCategories,
        MandateApprovalPolicy approvalPolicy,
        Long approvalThresholdPaise,
        Instant validFrom,
        Instant validUntil
    ) {
    }

    public record MandatePreview(String canonicalJson, String canonicalHashSha256) {
    }

    public record MandateSpendView(
        long maxPerTxnPaise, long maxDailyPaise, long maxMonthlyPaise,
        long dailySpendPaise, long monthlySpendPaise
    ) {
    }

    public record MandateSpend(UUID mandateId, MandateSpendView view) {
    }
}
