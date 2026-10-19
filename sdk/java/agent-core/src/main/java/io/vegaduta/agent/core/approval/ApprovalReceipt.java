package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Evidence of one human decision on one approval request: who approved or rejected what, exactly,
 * and when. The receipt carries a SHA-256 digest over its own content and, optionally, a signature
 * from a {@link ReceiptSigner}, so a regulator, auditor or insurer can check it long after the
 * approval store has been purged.
 *
 * <p>The payload itself is not copied; {@code payloadDigest} is the SHA-256 of the request's {@code
 * payloadJson}, which lets a receipt be matched against an archived payload without the receipt
 * itself leaking arguments.
 *
 * <p>Build receipts with {@link
 * ApprovalReceipts#from(io.vegaduta.agent.api.approval.ApprovalRequest)} and sign them with {@link
 * ApprovalReceipts#sign(ApprovalReceipt, ReceiptSigner)}.
 *
 * @param approvalId id of the approval request
 * @param actionType the request's action type, for example {@code "tool:refund_order"}
 * @param toolName the tool when the action type is a tool action, or {@code null}
 * @param payloadDigest SHA-256 hex of the request's payload JSON
 * @param summary the one line the approver saw
 * @param requestedAt when the request was recorded
 * @param decidedAt when the human decided
 * @param decidedBy who decided
 * @param decision approved or rejected
 * @param reason the approver's free text, or {@code null}
 * @param tenantId tenant of the run, or {@code null}
 * @param principal the principal that asked, or {@code null}
 * @param agentId the agent that asked, or {@code null}
 * @param runId the run that asked
 * @param digest SHA-256 hex over the canonical form of every field above
 * @param signature base64 signature over the canonical form, or {@code null} when unsigned
 * @param signerKeyId id of the key that produced {@code signature}, or {@code null}
 */
@Experimental
public record ApprovalReceipt(
    UUID approvalId,
    String actionType,
    @Nullable String toolName,
    String payloadDigest,
    String summary,
    Instant requestedAt,
    Instant decidedAt,
    String decidedBy,
    Decision decision,
    @Nullable String reason,
    @Nullable String tenantId,
    @Nullable String principal,
    @Nullable String agentId,
    String runId,
    String digest,
    @Nullable String signature,
    @Nullable String signerKeyId) {

  /** The human's decision. Execution outcomes are not part of a receipt. */
  public enum Decision {
    APPROVED,
    REJECTED
  }

  /** Format version carried in the first field of the compact line format. */
  public static final String FORMAT_VERSION = "vdr1";

  public ApprovalReceipt {
    Objects.requireNonNull(approvalId, "approvalId");
    Objects.requireNonNull(actionType, "actionType");
    Objects.requireNonNull(payloadDigest, "payloadDigest");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(requestedAt, "requestedAt");
    Objects.requireNonNull(decidedAt, "decidedAt");
    Objects.requireNonNull(decidedBy, "decidedBy");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(digest, "digest");
    if ((signature == null) != (signerKeyId == null)) {
      throw new IllegalArgumentException("signature and signerKeyId must be set together");
    }
  }

  /** Whether a signature is attached. Says nothing about its validity. */
  public boolean isSigned() {
    return signature != null;
  }

  /** The bytes a digest and a signature cover: every field except the digest and the signature. */
  public String canonical() {
    return ApprovalReceipts.canonical(this);
  }

  /** The same receipt with the given signature attached. */
  public ApprovalReceipt withSignature(String newSignature, String keyId) {
    return new ApprovalReceipt(
        approvalId,
        actionType,
        toolName,
        payloadDigest,
        summary,
        requestedAt,
        decidedAt,
        decidedBy,
        decision,
        reason,
        tenantId,
        principal,
        agentId,
        runId,
        digest,
        Objects.requireNonNull(newSignature, "signature"),
        Objects.requireNonNull(keyId, "keyId"));
  }

  /** The same receipt with no signature. */
  public ApprovalReceipt unsigned() {
    return new ApprovalReceipt(
        approvalId,
        actionType,
        toolName,
        payloadDigest,
        summary,
        requestedAt,
        decidedAt,
        decidedBy,
        decision,
        reason,
        tenantId,
        principal,
        agentId,
        runId,
        digest,
        null,
        null);
  }
}
