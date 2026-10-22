package com.agenticai.core.payments;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.payments.adapter.UpiAgentProtocolAdapter;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §E1's payment surface for agents - backs the {@code create_payment_link}, {@code
 * request_upi_collect} and {@code check_payment_status} tool callbacks (registered in core/agents)
 * and nothing else. A transaction's path through here:
 *
 * <ol>
 *   <li><b>Idempotency</b> - the key is derived from the call's own arguments (agent + tool +
 *       session + amount + payee + description), so a retried tool call resolves to the SAME row; a
 *       second call is a replay of state, never a second charge.
 *   <li><b>Mandate</b> - the agent's newest ACTIVE mandate; no mandate means the tool should never
 *       have been offered, and is refused anyway.
 *   <li><b>Enforcement</b> - {@link MandateEnforcer}: tombstone, Ed25519 signature over the stored
 *       canonical JSON, status, window, scope, caps, VPA/category allow-lists - then the
 *       append-only ledger CHARGE (cap reservation) under {@code FOR UPDATE}, all inside one
 *       transaction so a failed cap check can't leave a half-written row.
 *   <li><b>Approval</b> - the mandate's OWN policy ({@link MandateApprovalPolicy} ALWAYS /
 *       ABOVE_THRESHOLD / NEVER) decides, amount-aware - this is deliberately not {@code
 *       ApprovalPolicyService}'s action-type matching: the mandate IS the signed approval rule.
 *       When it requires a human, a PENDING {@code upi.mandate.execute} {@link ApprovalRequest} is
 *       recorded through the existing gate service and the transaction parks at PENDING_APPROVAL;
 *       the {@code PaymentMandateApprovalExecutor} runs it on approve, {@link
 *       PaymentExecutionService#settleRejected} on reject.
 *   <li><b>Execution</b> - {@link PaymentExecutionService} re-verifies the mandate at execution
 *       time and creates the live gateway link.
 * </ol>
 *
 * <p><b>Voice/autonomy bypass:</b> none exists here to bypass - the mandate check happens inside
 * the tool call itself, so a tool call that reaches this service is mandate-checked regardless of
 * which channel (chat, voice relay, job runner) or which autonomy tier invoked it. The generic
 * {@code GuardedToolCallback} gate ({@code mcp_tool.<name>}) wraps these callbacks additionally -
 * tenants who find the double-gate heavy can add an approval-policy override on the mcp_tool.*
 * action, and the mandate's own signed policy remains authoritative underneath.
 */
@Service
public class PaymentToolService {

    private static final Logger log = LoggerFactory.getLogger(PaymentToolService.class);

    /** The approval action type the payment executor registers under. */
    public static final String APPROVAL_ACTION_TYPE = "upi.mandate.execute";

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Duration DEFAULT_LINK_TTL = Duration.ofHours(24);

    private final PaymentMandateRepository mandateRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final MandateEnforcer enforcer;
    private final HumanApprovalGateService approvalGateService;
    private final PaymentExecutionService executionService;
    private final ObjectProvider<UpiAgentProtocolAdapter> adapterProvider;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;
    private final long linkTtlSeconds;

    public PaymentToolService(
            PaymentMandateRepository mandateRepository,
            PaymentTransactionRepository transactionRepository,
            MandateEnforcer enforcer,
            HumanApprovalGateService approvalGateService,
            PaymentExecutionService executionService,
            ObjectProvider<UpiAgentProtocolAdapter> adapterProvider,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager,
            @Value("${app.payments.link-ttl-seconds:86400}") long linkTtlSeconds) {
        this.mandateRepository = mandateRepository;
        this.transactionRepository = transactionRepository;
        this.enforcer = enforcer;
        this.approvalGateService = approvalGateService;
        this.executionService = executionService;
        this.adapterProvider = adapterProvider;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.linkTtlSeconds = linkTtlSeconds > 0 ? linkTtlSeconds : DEFAULT_LINK_TTL.getSeconds();
    }

    /**
     * Offer-time gate: the three tools only exist in an agent's manifest while an ACTIVE mandate
     * does.
     */
    public boolean hasActiveMandate(UUID agentId) {
        return HotpathLookups.tenant(
                "payments.active-mandate",
                agentId.toString(),
                () ->
                        mandateRepository
                                .findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
                                        agentId, MandateStatus.ACTIVE)
                                .map(m -> m.getValidUntil().isAfter(Instant.now()))
                                .orElse(false));
    }

    /**
     * {@code create_payment_link} - an open UPI payment link (payer picks their VPA on the hosted
     * page).
     */
    public PaymentOutcome createPaymentLink(
            UUID agentId,
            String sessionId,
            long amountPaise,
            String description,
            String customerName,
            String customerPhone,
            String merchantCategory) {
        return initiate(
                agentId,
                sessionId,
                PaymentTransactionType.PAYMENT_LINK,
                amountPaise,
                /* vpa */ null,
                description,
                merchantCategory);
    }

    /**
     * {@code request_upi_collect} - a collect request against a specific payee VPA (allow-list
     * enforced).
     */
    public PaymentOutcome requestUpiCollect(
            UUID agentId,
            String sessionId,
            long amountPaise,
            String vpa,
            String description,
            String merchantCategory) {
        if (vpa == null || vpa.isBlank()) {
            throw new MandateViolationException(
                    "VPA_REQUIRED", "request_upi_collect requires a payee vpa");
        }
        return initiate(
                agentId,
                sessionId,
                PaymentTransactionType.UPI_COLLECT,
                amountPaise,
                vpa.trim(),
                description,
                merchantCategory);
    }

    /** {@code check_payment_status} - the read half; strictly tenant-scoped (this repo is). */
    @Transactional(readOnly = true)
    public Map<String, Object> checkPaymentStatus(UUID agentId, UUID transactionId) {
        PaymentTransaction txn = transactionRepository.findById(transactionId).orElse(null);
        if (txn == null || !txn.getAgentId().equals(agentId)) {
            // A transaction belonging to another agent is indistinguishable
            // from a nonexistent one - never confirm a payment you can't see.
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "No payment transaction with id " + transactionId + " for this agent");
            return err;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", txn.getId().toString());
        out.put("status", txn.getStatus().name());
        out.put("paid", txn.getStatus() == PaymentTransactionStatus.CAPTURED);
        out.put("amountPaise", txn.getAmountPaise());
        out.put("currency", txn.getCurrency());
        out.put("type", txn.getType().name());
        if (txn.getShortUrl() != null) {
            out.put("shortUrl", txn.getShortUrl());
        }
        if (txn.getReceiptUrl() != null) {
            out.put("receiptUrl", txn.getReceiptUrl());
        }
        if (txn.getPaidAt() != null) {
            out.put("paidAt", txn.getPaidAt().toString());
        }
        if (txn.getStatus() == PaymentTransactionStatus.PENDING_APPROVAL
                && txn.getApprovalId() != null) {
            out.put("approvalId", txn.getApprovalId().toString());
            out.put("message", "This payment is waiting for a human's approval - do not retry it.");
        }
        if (txn.getFailureReason() != null) {
            out.put("failureReason", txn.getFailureReason());
        }
        return out;
    }

    private PaymentOutcome initiate(
            UUID agentId,
            String sessionId,
            PaymentTransactionType type,
            long amountPaise,
            String vpa,
            String description,
            String merchantCategory) {
        String idempotencyKey =
                idempotencyKey(agentId, sessionId, type, amountPaise, vpa, description);
        PaymentTransaction existing =
                transactionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            // Replayed tool call: report the existing row, never re-charge.
            return new PaymentOutcome(
                    existing.getId(),
                    existing.getStatus(),
                    existing.getShortUrl(),
                    existing.getApprovalId(),
                    true,
                    "This exact payment was already initiated - returning the existing"
                            + " transaction.");
        }
        if (adapterProvider.getIfAvailable() == null) {
            throw new MandateViolationException(
                    "PAYMENTS_UNAVAILABLE", "No UPI payment adapter is configured");
        }

        // An explicit transaction: a this.method() call to a @Transactional method bypasses the
        // proxy, and
        // reserveCaps is Propagation.MANDATORY (it threw IllegalTransactionStateException on every
        // call).
        UUID transactionId =
                transactionTemplate.execute(
                        status ->
                                reserveAndRecord(
                                        agentId,
                                        sessionId,
                                        type,
                                        amountPaise,
                                        vpa,
                                        description,
                                        merchantCategory,
                                        idempotencyKey));
        PaymentTransaction txn = transactionRepository.findById(transactionId).orElseThrow();

        if (txn.getStatus() == PaymentTransactionStatus.PENDING_APPROVAL) {
            return new PaymentOutcome(
                    txn.getId(),
                    txn.getStatus(),
                    null,
                    txn.getApprovalId(),
                    false,
                    "Payment recorded and waiting for human approval (approvalId "
                            + txn.getApprovalId()
                            + "). Do not retry - the link is created once approved.");
        }
        // No human needed under this mandate's policy - execute now, outside
        // the DB transaction that reserved the caps.
        executionService.execute(transactionId);
        PaymentTransaction settled = transactionRepository.findById(transactionId).orElseThrow();
        return new PaymentOutcome(
                settled.getId(),
                settled.getStatus(),
                settled.getShortUrl(),
                null,
                false,
                settled.getStatus() == PaymentTransactionStatus.AWAITING_PAYMENT
                        ? "Payment link created - share shortUrl with the customer; confirm via"
                                + " check_payment_status."
                        : "Payment could not be initiated: " + settled.getFailureReason());
    }

    /**
     * The transactional half, run inside {@link #transactionTemplate}: mandate -> enforce ->
     * reserve caps -> insert row -> (maybe) record the approval request. Caps are reserved BEFORE
     * approval so a pending transaction already counts against the limit - two big links can't both
     * sit pending and then blow the cap together.
     */
    private UUID reserveAndRecord(
            UUID agentId,
            String sessionId,
            PaymentTransactionType type,
            long amountPaise,
            String vpa,
            String description,
            String merchantCategory,
            String idempotencyKey) {
        Instant now = Instant.now();
        PaymentMandate mandate =
                mandateRepository
                        .findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
                                agentId, MandateStatus.ACTIVE)
                        .orElseThrow(
                                () ->
                                        new MandateViolationException(
                                                "NO_MANDATE",
                                                "This agent has no ACTIVE payment mandate - payment"
                                                        + " tools require one"));

        enforcer.assertMandateUsable(mandate, now);
        enforcer.assertTransactionShape(mandate, type, amountPaise, vpa, merchantCategory);

        PaymentTransaction txn =
                new PaymentTransaction(
                        mandate.getId(),
                        agentId,
                        sessionId,
                        type,
                        amountPaise,
                        vpa,
                        description,
                        merchantCategory,
                        idempotencyKey,
                        now.plusSeconds(linkTtlSeconds));
        transactionRepository.save(txn);
        // Cap reservation rides the same transaction: FOR UPDATE on the
        // mandate's latest ledger row serializes concurrent reservations.
        enforcer.reserveCaps(mandate, txn.getId(), amountPaise, now);

        if (mandate.getApprovalPolicy()
                .requiresApproval(amountPaise, mandate.getApprovalThresholdPaise())) {
            String requestedBy =
                    TenantContext.current()
                            .map(TenantContext::getSubject)
                            .orElse("agent:" + agentId);
            ApprovalRequest request =
                    approvalGateService.recordToolCallForApproval(
                            APPROVAL_ACTION_TYPE,
                            "Agent payment "
                                    + type
                                    + " of "
                                    + amountPaise
                                    + " paise"
                                    + (vpa != null ? " to " + vpa : "")
                                    + " under mandate "
                                    + mandate.getId(),
                            approvalPayload(txn, mandate),
                            requestedBy);
            txn.markPendingApproval(request.getId());
            transactionRepository.save(txn);
            audit(
                    "payment.transaction.pending_approval",
                    txn,
                    AuditOutcome.PENDING_APPROVAL,
                    "approvalId=" + request.getId());
        } else {
            audit(
                    "payment.transaction.initiated",
                    txn,
                    AuditOutcome.AUTO_APPROVED,
                    "mandate=" + mandate.getId() + " policy=" + mandate.getApprovalPolicy());
        }
        return txn.getId();
    }

    /**
     * The approval payload {@code PaymentMandateApprovalExecutor} replays from - carries mandate
     * context for the Approvals card.
     */
    private static String approvalPayload(PaymentTransaction txn, PaymentMandate mandate) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("transactionId", txn.getId().toString());
        payload.put("mandateId", mandate.getId().toString());
        payload.put("agentId", txn.getAgentId().toString());
        payload.put("type", txn.getType().name());
        payload.put("amountPaise", txn.getAmountPaise());
        payload.put("currency", txn.getCurrency());
        if (txn.getVpa() != null) {
            payload.put("payeeVpa", txn.getVpa());
        }
        if (txn.getDescription() != null) {
            payload.put("description", txn.getDescription());
        }
        ObjectNode mandateNode = payload.putObject("mandate");
        mandateNode.put("scope", mandate.getScope().name());
        mandateNode.put("maxPerTxnPaise", mandate.getMaxPerTxnPaise());
        mandateNode.put("maxDailyPaise", mandate.getMaxDailyPaise());
        mandateNode.put("maxMonthlyPaise", mandate.getMaxMonthlyPaise());
        mandateNode.put("grantedBy", mandate.getGrantedByUserId());
        mandateNode.put("validUntil", mandate.getValidUntil().toString());
        return payload.toString();
    }

    /**
     * Deterministic idempotency key: same agent + session + tool + arguments always hashes to the
     * same key, so a replayed call folds into the existing {@code payment_transaction} row (UNIQUE
     * column) instead of creating a second link. Deliberately argument-derived, not caller-
     * supplied - a model can't mint arbitrary keys.
     */
    private static String idempotencyKey(
            UUID agentId,
            String sessionId,
            PaymentTransactionType type,
            long amountPaise,
            String vpa,
            String description) {
        String material =
                agentId
                        + "|"
                        + (sessionId == null ? "" : sessionId)
                        + "|"
                        + type
                        + "|"
                        + amountPaise
                        + "|"
                        + (vpa == null ? "" : vpa)
                        + "|"
                        + (description == null ? "" : description);
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(material.getBytes(StandardCharsets.UTF_8)));
            return "upi:" + agentId + ":" + hash.substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void audit(
            String actionType, PaymentTransaction txn, AuditOutcome outcome, String detail) {
        try {
            String actor =
                    TenantContext.current()
                            .map(TenantContext::getSubject)
                            .orElse("agent:" + txn.getAgentId());
            auditLogService.record(
                    actor,
                    actionType,
                    "payment_transaction",
                    txn.getId().toString(),
                    outcome,
                    detail);
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} on transaction {}", actionType, txn.getId(), e);
        }
    }

    /**
     * What a tool call hands back to the model. {@code replayed} flags an idempotent fold-in so the
     * model knows it did not create a second payment.
     */
    public record PaymentOutcome(
            UUID transactionId,
            PaymentTransactionStatus status,
            String shortUrl,
            UUID approvalId,
            boolean replayed,
            String message) {}
}
