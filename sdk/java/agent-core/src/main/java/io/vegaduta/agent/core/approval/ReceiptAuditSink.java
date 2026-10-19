package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns every approval decision the {@link DefaultApprovalGate} audits into a signed {@link
 * ApprovalReceipt}. Register it as one more audit sink; it ignores every other event.
 *
 * <p>The gate's {@code approval.decided} event carries the approval id and the decision but not the
 * request itself, so the sink reads the decided request back from the {@link ApprovalStore} the
 * gate wrote to (the gate saves before it audits). Execution outcomes ({@code SUCCESS}, {@code
 * FAILURE}) and expiry are not human decisions and produce no receipt.
 *
 * <p>Like every sink, it never throws into the pipeline: a failure to build or store a receipt is
 * logged at WARN.
 */
@Experimental
public final class ReceiptAuditSink implements AuditSink {

  private static final Logger log = LoggerFactory.getLogger(ReceiptAuditSink.class);

  /** The audit action type {@link DefaultApprovalGate} uses for decisions. */
  public static final String DECISION_ACTION_TYPE = "approval.decided";

  private final ApprovalStore approvals;
  private final ReceiptStore receipts;
  private final @Nullable ReceiptSigner signer;
  private final @Nullable ReceiptVerifier verifier;
  private final java.util.concurrent.atomic.AtomicLong failures =
      new java.util.concurrent.atomic.AtomicLong();
  private volatile @Nullable String lastFailure;

  /** Unsigned receipts: digest only. */
  public ReceiptAuditSink(ApprovalStore approvals, ReceiptStore receipts) {
    this(approvals, receipts, null, null);
  }

  /**
   * Signed receipts. When the signer is also a {@link ReceiptVerifier} (HMAC), {@link
   * #verify(ApprovalReceipt)} uses it; for Ed25519 pass the verifier explicitly through the
   * four-argument constructor.
   */
  public ReceiptAuditSink(ApprovalStore approvals, ReceiptStore receipts, ReceiptSigner signer) {
    this(
        approvals,
        receipts,
        Objects.requireNonNull(signer, "signer"),
        signer instanceof ReceiptVerifier v ? v : null);
  }

  public ReceiptAuditSink(
      ApprovalStore approvals,
      ReceiptStore receipts,
      @Nullable ReceiptSigner signer,
      @Nullable ReceiptVerifier verifier) {
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.receipts = Objects.requireNonNull(receipts, "receipts");
    this.signer = signer;
    this.verifier = verifier;
    if (signer != null && verifier == null) {
      throw new IllegalArgumentException(
          "signer '"
              + signer.keyId()
              + "' is not a ReceiptVerifier; pass the matching verifier (for Ed25519 the public"
              + " key) or every receipt this sink writes would fail its own verify()");
    }
    if (signer != null && verifier != null && !signer.keyId().equals(verifier.keyId())) {
      throw new IllegalArgumentException(
          "signer key id '"
              + signer.keyId()
              + "' differs from verifier '"
              + verifier.keyId()
              + "'");
    }
  }

  @Override
  public void emit(AuditEvent event) {
    if (!isDecision(event)) {
      return;
    }
    try {
      UUID id = UUID.fromString(event.targetId());
      Optional<ApprovalRequest> request = approvals.find(id);
      if (request.isEmpty()) {
        log.warn("Approval {} was decided but is not in the approval store; no receipt", id);
        return;
      }
      ApprovalReceipt receipt = ApprovalReceipts.from(request.get());
      if (signer != null) {
        receipt = ApprovalReceipts.sign(receipt, signer);
      }
      receipts.save(receipt);
      log.debug("Receipt stored for approval {} ({})", id, receipt.decision());
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      lastFailure = e.toString();
      log.warn("Could not build a receipt for audit event {}: {}", event.id(), e.toString());
    }
  }

  /**
   * How many decisions produced no receipt (store or signer failure). The health signal for a
   * deployment where receipts are mandatory evidence: alert when it moves.
   */
  public long failures() {
    return failures.get();
  }

  /** The most recent failure, for diagnostics. */
  public Optional<String> lastFailure() {
    return Optional.ofNullable(lastFailure);
  }

  /** All receipts, oldest decision first, optionally limited to one tenant. */
  public List<ApprovalReceipt> receipts(@Nullable String tenantId) {
    return receipts.findByTenant(tenantId);
  }

  public Optional<ApprovalReceipt> receipt(UUID approvalId) {
    return receipts.find(approvalId);
  }

  /**
   * Whether the receipt is intact. With a verifier configured the receipt must carry a valid
   * signature from that key; without one the receipt must be unsigned and its digest must match.
   */
  public boolean verify(ApprovalReceipt receipt) {
    if (verifier != null) {
      return ApprovalReceipts.verify(receipt, verifier);
    }
    return !receipt.isSigned() && ApprovalReceipts.digestValid(receipt);
  }

  public ReceiptStore store() {
    return receipts;
  }

  static boolean isDecision(AuditEvent event) {
    return DECISION_ACTION_TYPE.equals(event.actionType())
        && (event.outcome() == AuditOutcome.APPROVED || event.outcome() == AuditOutcome.REJECTED);
  }
}
