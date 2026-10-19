package io.vegaduta.agent.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalActionExecutor;
import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.hook.HookEngine;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceiptAuditSinkTest {

  private final Instant now = Instant.parse("2026-10-14T10:00:00Z");
  private final InMemoryApprovalStore approvals = new InMemoryApprovalStore();
  private final InMemoryReceiptStore receipts = new InMemoryReceiptStore();
  private final HmacSha256ReceiptSigner signer =
      new HmacSha256ReceiptSigner(
          "receipts-2026", "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private final ReceiptAuditSink sink = new ReceiptAuditSink(approvals, receipts, signer);

  private DefaultApprovalGate gate() {
    ApprovalActionExecutorRegistry executors = new ApprovalActionExecutorRegistry();
    executors.register(
        new ApprovalActionExecutor() {
          @Override
          public String actionType() {
            return "tool:refund_order";
          }

          @Override
          public String execute(ApprovalRequest request) {
            return "{\"refunded\":true}";
          }
        });
    return new DefaultApprovalGate(
        approvals,
        new FourEyesAuthorizer(),
        executors,
        sink,
        new HookEngine(),
        Duration.ofHours(1),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private static RunContext ctx(String tenant) {
    return RunContext.builder().tenantId(tenant).principal("alice").agentId("bot").build();
  }

  @Test
  void approvalProducesSignedVerifiableReceipt() {
    DefaultApprovalGate gate = gate();
    ApprovalRequest request =
        gate.requestForTool(
            ToolCall.of("refund_order", "{\"orderId\":42}"), ctx("acme"), "Refund order 42");
    assertThat(receipts.size()).isZero();

    ApprovalRequest decided = gate.approve(request.id(), "bob");

    assertThat(decided.status()).isEqualTo(ApprovalStatus.EXECUTED);
    List<ApprovalReceipt> stored = sink.receipts("acme");
    assertThat(stored).hasSize(1);
    ApprovalReceipt receipt = stored.get(0);
    assertThat(receipt.approvalId()).isEqualTo(request.id());
    assertThat(receipt.decision()).isEqualTo(ApprovalReceipt.Decision.APPROVED);
    assertThat(receipt.decidedBy()).isEqualTo("bob");
    assertThat(receipt.decidedAt()).isEqualTo(now);
    assertThat(receipt.requestedAt()).isEqualTo(now);
    assertThat(receipt.toolName()).isEqualTo("refund_order");
    assertThat(receipt.summary()).isEqualTo("Refund order 42");
    assertThat(receipt.payloadDigest()).isEqualTo(ApprovalReceipts.sha256Hex("{\"orderId\":42}"));
    assertThat(receipt.signerKeyId()).isEqualTo("receipts-2026");
    assertThat(sink.verify(receipt)).isTrue();
    assertThat(sink.verify(receipt.unsigned())).isFalse();
  }

  @Test
  void rejectionKeepsReasonAndTenantsAreSeparated() {
    DefaultApprovalGate gate = gate();
    ApprovalRequest a = gate.request("ticket:close", "Close INC-1", "{}", ctx("acme"));
    ApprovalRequest b = gate.request("ticket:close", "Close INC-2", "{}", ctx("globex"));

    gate.reject(a.id(), "carol", "duplicate of INC-7");
    gate.approve(b.id(), "dave");

    assertThat(sink.receipts(null)).hasSize(2);
    assertThat(sink.receipts("acme")).hasSize(1);
    ApprovalReceipt rejected = sink.receipt(a.id()).orElseThrow();
    assertThat(rejected.decision()).isEqualTo(ApprovalReceipt.Decision.REJECTED);
    assertThat(rejected.reason()).isEqualTo("duplicate of INC-7");
    assertThat(rejected.toolName()).isNull();
    assertThat(sink.receipts("globex").get(0).decision())
        .isEqualTo(ApprovalReceipt.Decision.APPROVED);
    assertThat(sink.receipts("nobody")).isEmpty();
  }

  @Test
  void ignoresEventsThatAreNotHumanDecisionsAndNeverThrows() {
    sink.emit(AuditEvent.builder("tool.call", "tool", "search").build());
    sink.emit(
        AuditEvent.builder("approval.decided", "approval", UUID.randomUUID().toString())
            .outcome(AuditOutcome.SUCCESS)
            .build());
    sink.emit(
        AuditEvent.builder("approval.decided", "approval", UUID.randomUUID().toString())
            .outcome(AuditOutcome.EXPIRED)
            .build());
    sink.emit(
        AuditEvent.builder("approval.decided", "approval", "not-a-uuid")
            .outcome(AuditOutcome.APPROVED)
            .build());
    sink.emit(
        AuditEvent.builder("approval.decided", "approval", UUID.randomUUID().toString())
            .outcome(AuditOutcome.APPROVED)
            .build());

    assertThat(receipts.size()).isZero();
  }

  @Test
  void unsignedSinkVerifiesDigestOnly() {
    ReceiptAuditSink plain = new ReceiptAuditSink(approvals, new InMemoryReceiptStore());
    DefaultApprovalGate gate =
        new DefaultApprovalGate(
            approvals,
            ApprovalDecisionAuthorizer.permitAll(),
            new ApprovalActionExecutorRegistry(),
            plain,
            new HookEngine(),
            Duration.ofHours(1),
            Clock.fixed(now, ZoneOffset.UTC));
    ApprovalRequest r = gate.request("x", "s", "{}", RunContext.anonymous());
    gate.approve(r.id(), "anonymous");

    ApprovalReceipt receipt = plain.receipt(r.id()).orElseThrow();
    assertThat(receipt.isSigned()).isFalse();
    assertThat(plain.verify(receipt)).isTrue();
    assertThat(plain.verify(ApprovalReceipts.sign(receipt, signer))).isFalse();
  }

  @Test
  void receiptStoreIsAppendOnlyAndAnAsymmetricSignerNeedsItsVerifier() throws Exception {
    InMemoryReceiptStore store = new InMemoryReceiptStore();
    ApprovalReceipt first = ApprovalReceipts.from(decidedRequest("bob"));
    store.save(first);
    store.save(first); // identical: fine
    // Same approval id, a different approver: evidence must not be overwritten.
    ApprovalReceipt sameIdDifferentContent = first.withSignature("forged", "k9");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.save(sameIdDifferentContent))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.find(first.approvalId())).contains(first);

    java.security.KeyPair pair =
        java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Ed25519ReceiptSigner ed = new Ed25519ReceiptSigner("ed-1", pair.getPrivate());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ReceiptAuditSink(approvals, receipts, ed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("verifier");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> io.vegaduta.agent.core.Governance.builder().receipts(ed))
        .isInstanceOf(IllegalArgumentException.class);
    new ReceiptAuditSink(
        approvals, receipts, ed, new Ed25519ReceiptVerifier("ed-1", pair.getPublic()));
  }

  @Test
  void aFailingReceiptStoreIsCountedAsAFailure() {
    ReceiptStore broken =
        new ReceiptStore() {
          @Override
          public void save(ApprovalReceipt receipt) {
            throw new IllegalStateException("db down");
          }

          @Override
          public java.util.Optional<ApprovalReceipt> find(java.util.UUID approvalId) {
            return java.util.Optional.empty();
          }

          @Override
          public java.util.List<ApprovalReceipt> findByTenant(String tenantId) {
            return java.util.List.of();
          }
        };
    ReceiptAuditSink failing = new ReceiptAuditSink(approvals, broken, signer);
    ApprovalRequest request = decidedRequest("bob");
    approvals.save(request);

    failing.emit(
        io.vegaduta.agent.api.audit.AuditEvent.builder(
                ReceiptAuditSink.DECISION_ACTION_TYPE, "approval", request.id().toString())
            .context(request.context())
            .outcome(io.vegaduta.agent.api.audit.AuditOutcome.APPROVED)
            .build());

    assertThat(failing.failures()).isEqualTo(1);
    assertThat(failing.lastFailure()).hasValueSatisfying(m -> assertThat(m).contains("db down"));
  }

  private ApprovalRequest decidedRequest(String by) {
    return ApprovalRequest.pending(
            "tool:refund_order",
            "Refund order #42",
            "{\"orderId\":42}",
            ctx("acme"),
            now,
            now.plusSeconds(3600))
        .withDecision(ApprovalStatus.APPROVED, now.plusSeconds(90), by, null);
  }
}
