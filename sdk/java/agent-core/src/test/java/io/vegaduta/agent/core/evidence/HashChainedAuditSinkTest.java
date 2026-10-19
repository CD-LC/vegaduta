package io.vegaduta.agent.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HashChainedAuditSinkTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final RunContext CTX =
      RunContext.builder().tenantId("acme").agentId("payer").principal("alice").build();

  private static AuditEvent event(int i, AuditOutcome outcome) {
    return AuditEvent.builder("tool.call", "tool", "pay_invoice")
        .context(CTX)
        .outcome(outcome)
        .detail("call " + i)
        .risk(AsiRisk.ASI02)
        .attribute("callId", "c" + i)
        .attribute("risk", "HIGH")
        .at(T0.plusSeconds(i))
        .build();
  }

  private static List<AuditEvent> chain(HashChainedAuditSink sink, InMemoryAuditSink store, int n) {
    for (int i = 1; i <= n; i++) {
      sink.emit(event(i, AuditOutcome.SUCCESS));
    }
    return store.events();
  }

  @Test
  void chainsEventsAndPreservesIdentity() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink sink = new HashChainedAuditSink(store);
    AuditEvent original = event(1, AuditOutcome.SUCCESS);
    sink.emit(original);
    sink.emit(event(2, AuditOutcome.DENIED));

    List<AuditEvent> events = store.events();
    assertThat(events).hasSize(2);
    AuditEvent first = events.get(0);
    assertThat(first.id()).isEqualTo(original.id());
    assertThat(first.createdAt()).isEqualTo(original.createdAt());
    assertThat(first.attributes())
        .containsEntry("callId", "c1")
        .containsEntry(HashChainedAuditSink.SEQ, "1")
        .containsEntry(HashChainedAuditSink.PREV, HashChainedAuditSink.GENESIS)
        .containsKey(HashChainedAuditSink.HASH)
        .doesNotContainKey(HashChainedAuditSink.SIG);
    AuditEvent second = events.get(1);
    String firstHash = first.attributes().get(HashChainedAuditSink.HASH);
    assertThat(second.attributes())
        .containsEntry(HashChainedAuditSink.SEQ, "2")
        .containsEntry(HashChainedAuditSink.PREV, firstHash);
    assertThat(sink.size()).isEqualTo(2);
    assertThat(sink.head()).isEqualTo(second.attributes().get(HashChainedAuditSink.HASH));

    ChainVerification result = HashChainedAuditSink.verify(events, Optional.empty());
    assertThat(result.ok()).isTrue();
    assertThat(result.firstBrokenSeq()).isEqualTo(-1);
    assertThat(result.eventsChecked()).isEqualTo(2);
  }

  @Test
  void tamperingADetailBreaksVerificationAtThatSeq() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    List<AuditEvent> events = new ArrayList<>(chain(new HashChainedAuditSink(store), store, 5));
    AuditEvent victim = events.get(2);
    AuditEvent forged =
        new AuditEvent(
            victim.id(),
            victim.createdAt(),
            victim.actorId(),
            victim.actionType(),
            victim.targetType(),
            victim.targetId(),
            AuditOutcome.APPROVED,
            victim.detail(),
            victim.context(),
            victim.risks(),
            victim.attributes());
    events.set(2, forged);

    ChainVerification result = HashChainedAuditSink.verify(events, Optional.empty());
    assertThat(result.ok()).isFalse();
    assertThat(result.firstBrokenSeq()).isEqualTo(3);
    assertThat(result.eventsChecked()).isEqualTo(2);
    assertThat(result.message()).contains("seq 3").contains("chain.hash");
  }

  @Test
  void removingAnEventBreaksTheLinkAfterIt() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    List<AuditEvent> events = new ArrayList<>(chain(new HashChainedAuditSink(store), store, 4));
    events.remove(1);

    ChainVerification result = HashChainedAuditSink.verify(events, Optional.empty());
    assertThat(result.ok()).isFalse();
    assertThat(result.firstBrokenSeq()).isEqualTo(2);
    assertThat(result.message()).contains("expected seq 2 but found 3");
  }

  @Test
  void suffixOfAChainVerifiesWhenTheFirstPrevIsTrusted() {
    InMemoryAuditSink store = new InMemoryAuditSink(3);
    chain(new HashChainedAuditSink(store), store, 6);
    List<AuditEvent> kept = store.events();
    assertThat(kept).hasSize(3);
    assertThat(kept.get(0).attributes()).containsEntry(HashChainedAuditSink.SEQ, "4");

    assertThat(HashChainedAuditSink.verify(kept, Optional.empty()).ok()).isTrue();
  }

  @Test
  void hmacSignatureVerifiesAndFailsWithTheWrongKey() {
    byte[] key = "k".repeat(32).getBytes(StandardCharsets.UTF_8);
    byte[] other = "x".repeat(32).getBytes(StandardCharsets.UTF_8);
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink sink = new HashChainedAuditSink(store, new HmacSha256Signer(key));
    List<AuditEvent> events = chain(sink, store, 3);

    assertThat(events.get(0).attributes())
        .containsEntry(HashChainedAuditSink.ALG, HmacSha256Signer.ALGORITHM)
        .containsKey(HashChainedAuditSink.SIG);
    assertThat(HashChainedAuditSink.verify(events, Optional.of(new HmacSha256Signer(key))).ok())
        .isTrue();

    ChainVerification wrong =
        HashChainedAuditSink.verify(events, Optional.of(new HmacSha256Signer(other)));
    assertThat(wrong.ok()).isFalse();
    assertThat(wrong.firstBrokenSeq()).isEqualTo(1);
    assertThat(wrong.message()).contains("signature invalid");
  }

  @Test
  void ed25519SignatureVerifiesWithPublicKeyOnly() {
    Ed25519Signer signer = Ed25519Signer.generate();
    InMemoryAuditSink store = new InMemoryAuditSink();
    List<AuditEvent> events = chain(new HashChainedAuditSink(store, signer), store, 3);

    Verifier publicOnly = new Ed25519Verifier(signer.publicKey());
    assertThat(HashChainedAuditSink.verify(events, Optional.of(publicOnly)).ok()).isTrue();

    Verifier stranger = Ed25519Signer.generate().verifier();
    assertThat(HashChainedAuditSink.verify(events, Optional.of(stranger)).ok()).isFalse();
  }

  @Test
  void unsignedChainFailsWhenAVerifierIsRequired() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    List<AuditEvent> events = chain(new HashChainedAuditSink(store), store, 2);
    byte[] key = "k".repeat(32).getBytes(StandardCharsets.UTF_8);

    ChainVerification result =
        HashChainedAuditSink.verify(events, Optional.of(new HmacSha256Signer(key)));
    assertThat(result.ok()).isFalse();
    assertThat(result.message()).contains("missing chain.sig");
  }

  @Test
  void canonicalFormIgnoresChainAttributesAndKeyOrder() {
    AuditEvent plain = event(1, AuditOutcome.SUCCESS);
    Map<String, String> withChain = new LinkedHashMap<>();
    withChain.put(HashChainedAuditSink.HASH, "x");
    withChain.put("risk", "HIGH");
    withChain.put("callId", "c1");
    AuditEvent reordered = HashChainedAuditSink.withAttributes(plain, withChain);

    assertThat(HashChainedAuditSink.canonical(reordered))
        .isEqualTo(HashChainedAuditSink.canonical(plain))
        .startsWith("{\"actionType\":\"tool.call\"")
        .doesNotContain("chain.");
  }

  @Test
  void concurrentEmittersProduceOneUnbrokenChain() throws InterruptedException {
    InMemoryAuditSink store = new InMemoryAuditSink(10_000);
    HashChainedAuditSink sink = new HashChainedAuditSink(store);
    int threads = 8;
    int perThread = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++) {
      pool.execute(
          () -> {
            for (int i = 0; i < perThread; i++) {
              sink.emit(event(i, AuditOutcome.SUCCESS));
            }
            done.countDown();
          });
    }
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdown();

    // The delegate is called outside the chain lock, so storage order may differ from chain order;
    // chain.seq is the order of record.
    List<AuditEvent> events = new ArrayList<>(store.events());
    events.sort(java.util.Comparator.comparingLong(EvidenceBundle::seqOf));
    assertThat(events).hasSize(threads * perThread);
    assertThat(HashChainedAuditSink.verify(events, Optional.empty()).ok()).isTrue();
    assertThat(sink.size()).isEqualTo(threads * perThread);
  }

  @Test
  void attributeNamedLikeATopLevelFieldCannotShadowItInTheHash() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink sink = new HashChainedAuditSink(store);
    sink.emit(
        AuditEvent.builder("approval.decided", "approval", "a1")
            .context(CTX)
            .actor("bob")
            .outcome(AuditOutcome.APPROVED)
            .attribute("actionType", "tool:pay_invoice")
            .attribute("approvalId", "a1")
            .at(T0)
            .build());
    AuditEvent stored = store.events().get(0);

    // Tamper the real top-level actionType; the attribute of the same name is untouched.
    AuditEvent fieldTampered =
        new AuditEvent(
            stored.id(),
            stored.createdAt(),
            stored.actorId(),
            "approval.requested",
            stored.targetType(),
            stored.targetId(),
            stored.outcome(),
            stored.detail(),
            stored.context(),
            stored.risks(),
            stored.attributes());
    ChainVerification v = HashChainedAuditSink.verify(List.of(fieldTampered), Optional.empty());
    assertThat(v.ok()).isFalse();
    assertThat(v.message()).contains("chain.hash does not match");

    // Tamper an attribute value.
    Map<String, String> attrs = new LinkedHashMap<>(stored.attributes());
    attrs.put("approvalId", "a2");
    AuditEvent attrTampered = HashChainedAuditSink.withAttributes(stored, attrs);
    assertThat(HashChainedAuditSink.verify(List.of(attrTampered), Optional.empty()).ok()).isFalse();

    assertThat(HashChainedAuditSink.canonical(stored))
        .contains("\"actionType\":\"approval.decided\"")
        .contains("\"attr.actionType\":\"tool:pay_invoice\"");
  }

  @Test
  void aThrowingSignerKeepsTheChainContiguousAndIsVisibleToASignatureCheck() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    byte[] key = "k".repeat(32).getBytes(StandardCharsets.UTF_8);
    HmacSha256Signer real = new HmacSha256Signer(key);
    int[] calls = {0};
    Signer flaky =
        new Signer() {
          @Override
          public String algorithm() {
            return real.algorithm();
          }

          @Override
          public byte[] sign(byte[] data) {
            if (++calls[0] == 2) {
              throw new IllegalStateException("HSM unreachable");
            }
            return real.sign(data);
          }

          @Override
          public Verifier verifier() {
            return real.verifier();
          }
        };
    HashChainedAuditSink sink = new HashChainedAuditSink(store, flaky);
    List<AuditEvent> events = chain(sink, store, 3);

    assertThat(events).hasSize(3);
    assertThat(events.get(1).attributes())
        .containsKey(HashChainedAuditSink.SIG_ERROR)
        .doesNotContainKey(HashChainedAuditSink.SIG);
    assertThat(HashChainedAuditSink.verify(events, Optional.empty()).ok()).isTrue();
    ChainVerification signed = HashChainedAuditSink.verify(events, Optional.of(real));
    assertThat(signed.ok()).isFalse();
    assertThat(signed.firstBrokenSeq()).isEqualTo(2);
    assertThat(signed.message()).contains("signer failed");
  }

  @Test
  void aResumedWriterContinuesTheStoredChain() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink first = new HashChainedAuditSink(store);
    chain(first, store, 2);

    HashChainedAuditSink restarted =
        new HashChainedAuditSink(store, null, first.size(), first.head());
    restarted.emit(event(3, AuditOutcome.SUCCESS));

    assertThat(HashChainedAuditSink.verify(store.events(), Optional.empty()).ok()).isTrue();
    assertThat(store.events().get(2).attributes().get(HashChainedAuditSink.SEQ)).isEqualTo("3");

    HashChainedAuditSink fresh = new HashChainedAuditSink(store);
    fresh.emit(event(4, AuditOutcome.SUCCESS));
    assertThat(HashChainedAuditSink.verify(store.events(), Optional.empty()).message())
        .contains("expected seq 4 but found 1");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new HashChainedAuditSink(store, null, 0, "abc"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
