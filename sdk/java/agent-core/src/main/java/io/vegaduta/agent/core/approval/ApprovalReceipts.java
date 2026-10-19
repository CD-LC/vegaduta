package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.core.hook.JsonLite;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Builds, signs, verifies and serialises {@link ApprovalReceipt}s.
 *
 * <p>Two serialisations are offered, neither needing a JSON library:
 *
 * <ul>
 *   <li>{@link #encode(ApprovalReceipt)} / {@link #decode(String)}: one line per receipt, fields
 *       separated by {@code |}, percent-escaped, {@code ~} for an absent value. Suited to
 *       append-only logs and bulk export; round-trips exactly.
 *   <li>{@link #toJson(ApprovalReceipt)}: a flat JSON object for handing to other systems.
 * </ul>
 *
 * <p>The canonical form a digest and a signature cover is the compact line of every field except
 * the digest and signature fields, so the same bytes are recomputed on verification regardless of
 * which serialisation carried the receipt.
 */
@Experimental
public final class ApprovalReceipts {

  private static final char SEPARATOR = '|';
  private static final String ABSENT = "~";
  private static final int FIELD_COUNT = 17;

  private ApprovalReceipts() {}

  /**
   * Builds an unsigned receipt for a decided request.
   *
   * @throws IllegalArgumentException when the request is still pending, has expired, or carries no
   *     decision
   */
  public static ApprovalReceipt from(ApprovalRequest request) {
    ApprovalReceipt.Decision decision = decisionOf(request.status());
    Instant decidedAt = request.decidedAt();
    String decidedBy = request.decidedBy();
    if (decidedAt == null || decidedBy == null) {
      throw new IllegalArgumentException(
          "Approval request " + request.id() + " has no recorded decision");
    }
    String actionType = request.actionType();
    String toolName =
        actionType.startsWith(DefaultApprovalGate.TOOL_ACTION_PREFIX)
            ? actionType.substring(DefaultApprovalGate.TOOL_ACTION_PREFIX.length())
            : null;
    ApprovalReceipt withoutDigest =
        new ApprovalReceipt(
            request.id(),
            actionType,
            toolName,
            sha256Hex(request.payloadJson()),
            request.summary(),
            request.createdAt(),
            decidedAt,
            decidedBy,
            decision,
            request.decisionReason(),
            request.context().tenantId(),
            request.context().principal(),
            request.context().agentId(),
            request.context().runId(),
            "",
            null,
            null);
    return withDigest(withoutDigest, sha256Hex(canonical(withoutDigest)));
  }

  /** Signs the receipt's canonical form and attaches the signature and key id. */
  public static ApprovalReceipt sign(ApprovalReceipt receipt, ReceiptSigner signer) {
    String signature = signer.sign(canonicalBytes(receipt));
    return receipt.withSignature(signature, signer.keyId());
  }

  /** Whether the receipt's digest still matches its content. Detects any edited field. */
  public static boolean digestValid(ApprovalReceipt receipt) {
    return constantTimeEquals(receipt.digest(), sha256Hex(canonical(receipt)));
  }

  /**
   * Whether the digest is intact and the signature was produced by the verifier's key over this
   * exact content. An unsigned receipt, or one signed by another key id, fails.
   */
  public static boolean verify(ApprovalReceipt receipt, ReceiptVerifier verifier) {
    if (!digestValid(receipt)) {
      return false;
    }
    String signature = receipt.signature();
    if (signature == null || !verifier.keyId().equals(receipt.signerKeyId())) {
      return false;
    }
    return verifier.verify(canonicalBytes(receipt), signature);
  }

  /** The compact line form; see the class comment. */
  public static String encode(ApprovalReceipt receipt) {
    StringBuilder sb = new StringBuilder(256);
    appendSigned(sb, receipt);
    sb.append(SEPARATOR).append(escape(receipt.digest()));
    sb.append(SEPARATOR).append(encodeNullable(receipt.signature()));
    sb.append(SEPARATOR).append(encodeNullable(receipt.signerKeyId()));
    return sb.toString();
  }

  /**
   * Parses a line produced by {@link #encode(ApprovalReceipt)}. Decoding does not verify; call
   * {@link #digestValid(ApprovalReceipt)} or {@link #verify(ApprovalReceipt, ReceiptVerifier)}.
   *
   * @throws IllegalArgumentException when the line is not a receipt
   */
  public static ApprovalReceipt decode(String line) {
    List<String> parts = split(line);
    if (parts.size() != FIELD_COUNT + 1 || !ApprovalReceipt.FORMAT_VERSION.equals(parts.get(0))) {
      throw new IllegalArgumentException("Not a " + ApprovalReceipt.FORMAT_VERSION + " receipt");
    }
    try {
      int i = 1;
      return new ApprovalReceipt(
          UUID.fromString(required(parts.get(i++), "approvalId")),
          required(parts.get(i++), "actionType"),
          decodeNullable(parts.get(i++)),
          required(parts.get(i++), "payloadDigest"),
          required(parts.get(i++), "summary"),
          Instant.parse(required(parts.get(i++), "requestedAt")),
          Instant.parse(required(parts.get(i++), "decidedAt")),
          required(parts.get(i++), "decidedBy"),
          ApprovalReceipt.Decision.valueOf(required(parts.get(i++), "decision")),
          decodeNullable(parts.get(i++)),
          decodeNullable(parts.get(i++)),
          decodeNullable(parts.get(i++)),
          decodeNullable(parts.get(i++)),
          required(parts.get(i++), "runId"),
          required(parts.get(i++), "digest"),
          decodeNullable(parts.get(i++)),
          decodeNullable(parts.get(i)));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Malformed receipt: " + e.getMessage(), e);
    }
  }

  /** A flat JSON object with every field; absent values are JSON {@code null}. */
  public static String toJson(ApprovalReceipt receipt) {
    StringBuilder sb = new StringBuilder(512).append('{');
    field(sb, "format", ApprovalReceipt.FORMAT_VERSION);
    field(sb, "approvalId", receipt.approvalId().toString());
    field(sb, "actionType", receipt.actionType());
    field(sb, "toolName", receipt.toolName());
    field(sb, "payloadDigest", receipt.payloadDigest());
    field(sb, "summary", receipt.summary());
    field(sb, "requestedAt", receipt.requestedAt().toString());
    field(sb, "decidedAt", receipt.decidedAt().toString());
    field(sb, "decidedBy", receipt.decidedBy());
    field(sb, "decision", receipt.decision().name());
    field(sb, "reason", receipt.reason());
    field(sb, "tenantId", receipt.tenantId());
    field(sb, "principal", receipt.principal());
    field(sb, "agentId", receipt.agentId());
    field(sb, "runId", receipt.runId());
    field(sb, "digest", receipt.digest());
    field(sb, "signature", receipt.signature());
    field(sb, "signerKeyId", receipt.signerKeyId());
    sb.setLength(sb.length() - 1);
    return sb.append('}').toString();
  }

  /** Lower-case hex SHA-256 of the UTF-8 bytes. */
  public static String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
    }
  }

  static String canonical(ApprovalReceipt receipt) {
    StringBuilder sb = new StringBuilder(256);
    appendSigned(sb, receipt);
    return sb.toString();
  }

  private static byte[] canonicalBytes(ApprovalReceipt receipt) {
    return canonical(receipt).getBytes(StandardCharsets.UTF_8);
  }

  private static void appendSigned(StringBuilder sb, ApprovalReceipt r) {
    sb.append(ApprovalReceipt.FORMAT_VERSION);
    sb.append(SEPARATOR).append(escape(r.approvalId().toString()));
    sb.append(SEPARATOR).append(escape(r.actionType()));
    sb.append(SEPARATOR).append(encodeNullable(r.toolName()));
    sb.append(SEPARATOR).append(escape(r.payloadDigest()));
    sb.append(SEPARATOR).append(escape(r.summary()));
    sb.append(SEPARATOR).append(escape(r.requestedAt().toString()));
    sb.append(SEPARATOR).append(escape(r.decidedAt().toString()));
    sb.append(SEPARATOR).append(escape(r.decidedBy()));
    sb.append(SEPARATOR).append(escape(r.decision().name()));
    sb.append(SEPARATOR).append(encodeNullable(r.reason()));
    sb.append(SEPARATOR).append(encodeNullable(r.tenantId()));
    sb.append(SEPARATOR).append(encodeNullable(r.principal()));
    sb.append(SEPARATOR).append(encodeNullable(r.agentId()));
    sb.append(SEPARATOR).append(escape(r.runId()));
  }

  private static ApprovalReceipt withDigest(ApprovalReceipt r, String digest) {
    return new ApprovalReceipt(
        r.approvalId(),
        r.actionType(),
        r.toolName(),
        r.payloadDigest(),
        r.summary(),
        r.requestedAt(),
        r.decidedAt(),
        r.decidedBy(),
        r.decision(),
        r.reason(),
        r.tenantId(),
        r.principal(),
        r.agentId(),
        r.runId(),
        digest,
        null,
        null);
  }

  private static ApprovalReceipt.Decision decisionOf(ApprovalStatus status) {
    return switch (status) {
      case REJECTED -> ApprovalReceipt.Decision.REJECTED;
      case APPROVED, EXECUTED, FAILED -> ApprovalReceipt.Decision.APPROVED;
      case PENDING, EXPIRED ->
          throw new IllegalArgumentException("No receipt for a request in state " + status);
    };
  }

  private static void field(StringBuilder sb, String name, @Nullable String value) {
    sb.append('"').append(JsonLite.escape(name)).append("\":");
    if (value == null) {
      sb.append("null");
    } else {
      sb.append('"').append(JsonLite.escape(value)).append('"');
    }
    sb.append(',');
  }

  private static String encodeNullable(@Nullable String value) {
    return value == null ? ABSENT : escape(value);
  }

  private static @Nullable String decodeNullable(String field) {
    return ABSENT.equals(field) ? null : unescape(field);
  }

  private static String required(String field, String name) {
    if (ABSENT.equals(field)) {
      throw new IllegalArgumentException(name + " is required");
    }
    return unescape(field);
  }

  /** Percent-escapes the separator, the absent marker, the escape character and line breaks. */
  static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '%' -> sb.append("%25");
        case '|' -> sb.append("%7C");
        case '~' -> sb.append("%7E");
        case '\n' -> sb.append("%0A");
        case '\r' -> sb.append("%0D");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  static String unescape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '%') {
        if (i + 2 >= s.length()) {
          throw new IllegalArgumentException("Truncated escape in '" + s + "'");
        }
        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
        i += 2;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static List<String> split(String line) {
    List<String> parts = new ArrayList<>(FIELD_COUNT + 1);
    int start = 0;
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) == SEPARATOR) {
        parts.add(line.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(line.substring(start));
    return parts;
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
