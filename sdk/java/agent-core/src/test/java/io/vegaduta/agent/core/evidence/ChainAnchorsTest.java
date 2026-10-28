package io.vegaduta.agent.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChainAnchorsTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final RunContext CTX = RunContext.builder().tenantId("acme").agentId("a").build();

  private static List<AuditEvent> chain(int n) {
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink sink = new HashChainedAuditSink(store);
    for (int i = 1; i <= n; i++) {
      sink.emit(
          AuditEvent.builder("tool.call", "tool", "t" + i)
              .context(CTX)
              .outcome(AuditOutcome.SUCCESS)
              .at(T0.plusSeconds(i))
              .build());
    }
    return store.events();
  }

  private static String hashOf(AuditEvent e) {
    return e.attributes().get(HashChainedAuditSink.HASH);
  }

  @Test
  void noAnchorsKeepsTheOldBehaviour() {
    List<AuditEvent> events = chain(5);
    assertThat(HashChainedAuditSink.verify(events.subList(2, 5), Optional.empty()).ok()).isTrue();
    assertThat(HashChainedAuditSink.verify(events.subList(0, 3), Optional.empty()).ok()).isTrue();
    assertThat(ChainAnchors.none().any()).isFalse();
  }

  @Test
  void matchingAnchorsPassOnAFullChainAndOnAnInnerSlice() {
    List<AuditEvent> events = chain(6);
    ChainAnchors full =
        ChainAnchors.none()
            .withFirstSeq(1)
            .withFirstPrev(HashChainedAuditSink.GENESIS)
            .withLastSeq(6)
            .withHeadHash(hashOf(events.get(5)));
    assertThat(HashChainedAuditSink.verify(events, Optional.empty(), full).ok()).isTrue();

    // a slice verified against the anchors recorded for that slice
    List<AuditEvent> slice = events.subList(2, 5);
    ChainAnchors sliceAnchors =
        ChainAnchors.none()
            .withFirstSeq(3)
            .withFirstPrev(hashOf(events.get(1)))
            .withLastSeq(5)
            .withHeadHash(hashOf(events.get(4)));
    assertThat(HashChainedAuditSink.verify(slice, Optional.empty(), sliceAnchors).ok()).isTrue();
  }

  @Test
  void eachAnchorNamesItselfWhenItMismatches() {
    List<AuditEvent> events = chain(4);
    String head = hashOf(events.get(3));

    ChainVerification firstSeq =
        HashChainedAuditSink.verify(
            events.subList(1, 4), Optional.empty(), ChainAnchors.none().withFirstSeq(1));
    assertThat(firstSeq.ok()).isFalse();
    assertThat(firstSeq.firstBrokenSeq()).isEqualTo(2);
    assertThat(firstSeq.message()).contains("expectedFirstSeq");

    ChainVerification firstPrev =
        HashChainedAuditSink.verify(
            events.subList(1, 4),
            Optional.empty(),
            ChainAnchors.none().withFirstPrev(HashChainedAuditSink.GENESIS));
    assertThat(firstPrev.ok()).isFalse();
    assertThat(firstPrev.message()).contains("expectedFirstPrev");

    ChainVerification lastSeq =
        HashChainedAuditSink.verify(
            events.subList(0, 3), Optional.empty(), ChainAnchors.none().withLastSeq(4));
    assertThat(lastSeq.ok()).isFalse();
    assertThat(lastSeq.firstBrokenSeq()).isEqualTo(3);
    assertThat(lastSeq.message()).contains("expectedLastSeq");

    ChainVerification headHash =
        HashChainedAuditSink.verify(
            events.subList(0, 3), Optional.empty(), ChainAnchors.none().withHeadHash(head));
    assertThat(headHash.ok()).isFalse();
    assertThat(headHash.message()).contains("expectedHeadHash");
  }

  @Test
  void anAnchorOnAnEmptyLogFailsInsteadOfPassingVacuously() {
    ChainVerification v =
        HashChainedAuditSink.verify(
            List.of(), Optional.empty(), ChainAnchors.none().withLastSeq(3));
    assertThat(v.ok()).isFalse();
    assertThat(v.message()).contains("anchor");
    assertThat(HashChainedAuditSink.verify(List.of(), Optional.empty()).ok()).isTrue();
  }

  @Test
  void bundleExposesAnchorsFromTheFullChainAndChecksSuppliedOnes() {
    List<AuditEvent> events = chain(5);
    List<AuditEvent> shuffled = new ArrayList<>(events);
    Collections.reverse(shuffled);

    EvidenceBundle bundle = EvidenceBundle.of(shuffled);
    assertThat(bundle.chainVerification().ok()).isTrue();
    assertThat(bundle.firstSeq()).hasValue(1);
    assertThat(bundle.firstPrev()).hasValue(HashChainedAuditSink.GENESIS);
    assertThat(bundle.lastSeq()).hasValue(5);
    assertThat(bundle.headHash()).hasValue(hashOf(events.get(4)));

    // a period filter narrows the events, not the anchors
    EvidenceBundle windowed =
        EvidenceBundle.builder()
            .events(events)
            .from(T0.plusSeconds(2))
            .to(T0.plusSeconds(4))
            .build();
    assertThat(windowed.events()).hasSize(2);
    assertThat(windowed.lastSeq()).hasValue(5);

    EvidenceBundle ok =
        EvidenceBundle.builder()
            .events(events)
            .expectedFirstSeq(1)
            .expectedFirstPrev(HashChainedAuditSink.GENESIS)
            .expectedLastSeq(5)
            .expectedHeadHash(hashOf(events.get(4)))
            .build();
    assertThat(ok.chainVerification().ok()).isTrue();

    EvidenceBundle truncated =
        EvidenceBundle.builder()
            .events(events.subList(0, 4))
            .expectedHeadHash(hashOf(events.get(4)))
            .build();
    assertThat(truncated.chainVerification().ok()).isFalse();
    assertThat(truncated.chainVerification().message()).contains("expectedHeadHash");
  }

  @Test
  void unchainedBundleHasNoAnchorsAndAnAnchorFailsIt() {
    AuditEvent plain =
        AuditEvent.builder("tool.call", "tool", "t").context(CTX).at(T0).build();
    EvidenceBundle bundle = EvidenceBundle.of(List.of(plain));
    assertThat(bundle.firstSeq()).isEmpty();
    assertThat(bundle.lastSeq()).isEmpty();
    assertThat(bundle.headHash()).isEmpty();
    assertThat(bundle.firstPrev()).isEmpty();

    EvidenceBundle anchored =
        EvidenceBundle.builder().events(List.of(plain)).expectedLastSeq(1).build();
    assertThat(anchored.chainVerification().ok()).isFalse();
    assertThat(anchored.chainVerification().message()).contains("anchor");

    String json = new EuAiActEvidenceExporter(bundle).toJsonLines();
    assertThat(json).contains("\"headHash\":null").contains("\"firstSeq\":null");
  }
}
