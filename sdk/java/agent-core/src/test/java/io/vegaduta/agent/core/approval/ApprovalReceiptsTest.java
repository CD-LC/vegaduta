package io.vegaduta.agent.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalReceiptsTest {

  private static final Instant T0 = Instant.parse("2026-10-14T09:00:00Z");
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  private static ApprovalRequest decided(ApprovalStatus status, String reason) {
    RunContext ctx =
        RunContext.builder()
            .tenantId("acme")
            .principal("alice")
            .agentId("refund-bot")
            .runId("run-1")
            .build();
    return ApprovalRequest.pending(
            "tool:refund_order",
            "Refund order #42 | 120.00 EUR ~ full",
            "{\"orderId\":42,\"amount\":120.0}",
            ctx,
            T0,
            T0.plusSeconds(3600))
        .withDecision(status, T0.plusSeconds(90), "bob", reason);
  }

  @Test
  void buildsReceiptFromDecidedRequestWithPayloadDigest() {
    ApprovalRequest request = decided(ApprovalStatus.APPROVED, null);

    ApprovalReceipt receipt = ApprovalReceipts.from(request);

    assertThat(receipt.approvalId()).isEqualTo(request.id());
    assertThat(receipt.toolName()).isEqualTo("refund_order");
    assertThat(receipt.decision()).isEqualTo(ApprovalReceipt.Decision.APPROVED);
    assertThat(receipt.decidedBy()).isEqualTo("bob");
    assertThat(receipt.principal()).isEqualTo("alice");
    assertThat(receipt.tenantId()).isEqualTo("acme");
    assertThat(receipt.payloadDigest())
        .isEqualTo(ApprovalReceipts.sha256Hex("{\"orderId\":42,\"amount\":120.0}"))
        .hasSize(64);
    assertThat(receipt.isSigned()).isFalse();
    assertThat(ApprovalReceipts.digestValid(receipt)).isTrue();
    assertThat(ApprovalReceipts.from(request).digest()).isEqualTo(receipt.digest());
  }

  @Test
  void refusesPendingAndExpiredRequests() {
    ApprovalRequest pending =
        ApprovalRequest.pending("x", "s", "{}", RunContext.anonymous(), T0, T0.plusSeconds(1));
    assertThatThrownBy(() -> ApprovalReceipts.from(pending))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalReceipts.from(pending.withStatus(ApprovalStatus.EXPIRED)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hmacSignatureVerifiesAndTamperIsDetected() {
    HmacSha256ReceiptSigner signer = new HmacSha256ReceiptSigner("k1", KEY);
    ApprovalReceipt signed =
        ApprovalReceipts.sign(
            ApprovalReceipts.from(decided(ApprovalStatus.REJECTED, "too big")), signer);

    assertThat(signed.signerKeyId()).isEqualTo("k1");
    assertThat(ApprovalReceipts.verify(signed, signer)).isTrue();

    ApprovalReceipt tampered =
        ApprovalReceipts.decode(ApprovalReceipts.encode(signed).replace("|bob|", "|mallory|"));
    assertThat(tampered.decidedBy()).isEqualTo("mallory");
    assertThat(ApprovalReceipts.digestValid(tampered)).isFalse();
    assertThat(ApprovalReceipts.verify(tampered, signer)).isFalse();

    HmacSha256ReceiptSigner otherKey =
        new HmacSha256ReceiptSigner(
            "k1", "another-secret-another-secret!!".getBytes(StandardCharsets.UTF_8));
    assertThat(ApprovalReceipts.verify(signed, otherKey)).isFalse();
    HmacSha256ReceiptSigner otherId = new HmacSha256ReceiptSigner("k2", KEY);
    assertThat(ApprovalReceipts.verify(signed, otherId)).isFalse();
    assertThat(ApprovalReceipts.verify(signed.unsigned(), signer)).isFalse();
  }

  @Test
  void ed25519SignatureVerifiesWithPublicKeyOnly() throws NoSuchAlgorithmException {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Ed25519ReceiptSigner signer = new Ed25519ReceiptSigner("ed-1", pair.getPrivate());
    Ed25519ReceiptVerifier verifier = new Ed25519ReceiptVerifier("ed-1", pair.getPublic());
    Ed25519ReceiptVerifier wrong = new Ed25519ReceiptVerifier("ed-1", other.getPublic());

    ApprovalReceipt signed =
        ApprovalReceipts.sign(
            ApprovalReceipts.from(decided(ApprovalStatus.APPROVED, null)), signer);

    assertThat(ApprovalReceipts.verify(signed, verifier)).isTrue();
    assertThat(ApprovalReceipts.verify(signed, wrong)).isFalse();
    assertThat(verifier.verify(new byte[] {1}, "not base64!")).isFalse();
  }

  @Test
  void compactLineRoundTripsEveryFieldIncludingSeparatorsAndNulls() {
    HmacSha256ReceiptSigner signer = new HmacSha256ReceiptSigner("k1", KEY);
    ApprovalReceipt signed =
        ApprovalReceipts.sign(
            ApprovalReceipts.from(decided(ApprovalStatus.APPROVED, null)), signer);

    String line = ApprovalReceipts.encode(signed);
    assertThat(line).startsWith("vdr1|").doesNotContain("\n");
    ApprovalReceipt decoded = ApprovalReceipts.decode(line);

    assertThat(decoded).isEqualTo(signed);
    assertThat(decoded.summary()).isEqualTo("Refund order #42 | 120.00 EUR ~ full");
    assertThat(decoded.reason()).isNull();
    assertThat(ApprovalReceipts.verify(decoded, signer)).isTrue();

    ApprovalReceipt anonymous =
        ApprovalReceipts.from(
            ApprovalRequest.pending(
                    "ticket", "s", "{}", RunContext.ofRun("r"), T0, T0.plusSeconds(5))
                .withDecision(ApprovalStatus.REJECTED, T0, "bob", "line1\nline2 100%"));
    ApprovalReceipt back = ApprovalReceipts.decode(ApprovalReceipts.encode(anonymous));
    assertThat(back).isEqualTo(anonymous);
    assertThat(back.toolName()).isNull();
    assertThat(back.reason()).isEqualTo("line1\nline2 100%");
  }

  @Test
  void decodeRejectsGarbage() {
    assertThatThrownBy(() -> ApprovalReceipts.decode("hello"))
        .isInstanceOf(IllegalArgumentException.class);
    String badId = "vdr1|not-a-uuid|a|~|d|s|x|y|b|APPROVED|~|~|~|~|r|d|~|~";
    assertThatThrownBy(() -> ApprovalReceipts.decode(badId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed");
  }

  @Test
  void jsonExportCarriesEveryFieldWithNulls() {
    ApprovalReceipt receipt = ApprovalReceipts.from(decided(ApprovalStatus.REJECTED, "say \"no\""));

    String json = ApprovalReceipts.toJson(receipt);

    assertThat(json)
        .startsWith("{\"format\":\"vdr1\",\"approvalId\":\"" + receipt.approvalId() + "\"")
        .contains("\"decision\":\"REJECTED\"")
        .contains("\"reason\":\"say \\\"no\\\"\"")
        .contains("\"signature\":null,\"signerKeyId\":null}")
        .contains("\"digest\":\"" + receipt.digest() + "\"");
  }

  @Test
  void signatureAndKeyIdMustTravelTogether() {
    ApprovalReceipt receipt = ApprovalReceipts.from(decided(ApprovalStatus.APPROVED, null));
    assertThatThrownBy(
            () ->
                new ApprovalReceipt(
                    UUID.randomUUID(),
                    receipt.actionType(),
                    null,
                    receipt.payloadDigest(),
                    receipt.summary(),
                    receipt.requestedAt(),
                    receipt.decidedAt(),
                    receipt.decidedBy(),
                    receipt.decision(),
                    null,
                    null,
                    null,
                    null,
                    receipt.runId(),
                    receipt.digest(),
                    "sig",
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
