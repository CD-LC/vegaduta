package io.vegaduta.agent.api.mandate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.risk.RiskLabel;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MandateTest {

  private static final Instant EXPIRY = Instant.parse("2030-01-01T00:00:00Z");

  private static Mandate root() {
    return Mandate.root("platform", "orchestrator")
        .principal("alice@example.com")
        .allowTools("jira.*", "slack.post", "crm.read")
        .maxRisk(RiskLabel.HIGH)
        .maxDepth(2)
        .expiresAt(EXPIRY)
        .build();
  }

  @Test
  void rootHasDepthZeroNoParentAndAVerifiableDigest() {
    Mandate root = root();
    assertThat(root.depth()).isZero();
    assertThat(root.parentId()).isNull();
    assertThat(root.digest()).hasSize(64).isEqualTo(root.expectedDigest(null));
    assertThat(root.permits("jira.issue.create")).isTrue();
    assertThat(root.permits("slack.post")).isTrue();
    assertThat(root.permits("slack.delete")).isFalse();
    assertThat(root.isExpired(EXPIRY.minusSeconds(1))).isFalse();
    assertThat(root.isExpired(EXPIRY)).isTrue();
  }

  @Test
  void deriveNarrowsToolsRiskAndExpiry() {
    Mandate root = root();
    Mandate child =
        root.derive(
            "jira-agent",
            Set.of("jira.issue.*", "crm.read"),
            RiskLabel.MEDIUM,
            EXPIRY.minusSeconds(60));

    assertThat(child.issuer()).isEqualTo("orchestrator");
    assertThat(child.subject()).isEqualTo("jira-agent");
    assertThat(child.principal()).isEqualTo("alice@example.com");
    assertThat(child.depth()).isEqualTo(1);
    assertThat(child.maxDepth()).isEqualTo(2);
    assertThat(child.parentId()).isEqualTo(root.id());
    assertThat(child.digest()).isEqualTo(child.expectedDigest(root.digest()));
    assertThat(child.permits("jira.issue.comment")).isTrue();
    assertThat(child.permits("jira.project.delete")).isFalse();
    assertThat(child.permits("slack.post")).isFalse();
  }

  @Test
  void deriveRejectsEveryKindOfWidening() {
    Mandate root = root();
    assertThatThrownBy(() -> root.derive("x", Set.of("github.*"), RiskLabel.LOW, EXPIRY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("github.*");
    assertThatThrownBy(() -> root.derive("x", Set.of("*"), RiskLabel.LOW, EXPIRY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not covered");
    assertThatThrownBy(() -> root.derive("x", Set.of("crm.read"), RiskLabel.CRITICAL, EXPIRY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CRITICAL");
    assertThatThrownBy(
            () -> root.derive("x", Set.of("crm.read"), RiskLabel.LOW, EXPIRY.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expiry");
  }

  @Test
  void deriveStopsAtMaxDepth() {
    Mandate hop1 = root().derive("a", Set.of("crm.read"), RiskLabel.LOW, EXPIRY);
    Mandate hop2 = hop1.derive("b", Set.of("crm.read"), RiskLabel.LOW, EXPIRY);
    assertThat(hop2.depth()).isEqualTo(2);
    assertThatThrownBy(() -> hop2.derive("c", Set.of("crm.read"), RiskLabel.LOW, EXPIRY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("depth");
  }

  @Test
  void verifyChainAcceptsAnHonestChainAndReturnsTheLeaf() {
    Mandate root = root();
    Mandate hop1 = root.derive("a", Set.of("jira.*"), RiskLabel.MEDIUM, EXPIRY);
    Mandate hop2 = hop1.derive("b", Set.of("jira.issue.*"), RiskLabel.LOW, EXPIRY);
    assertThat(Mandates.verifyChain(List.of(root, hop1, hop2))).isEqualTo(hop2);
  }

  @Test
  void verifyChainDetectsTamperedDigestAndWidenedHop() {
    Mandate root = root();
    Mandate hop1 = root.derive("a", Set.of("jira.*"), RiskLabel.MEDIUM, EXPIRY);
    Mandate widened =
        new Mandate(
            hop1.id(),
            hop1.issuer(),
            hop1.subject(),
            hop1.principal(),
            Set.of("jira.*", "slack.post"),
            hop1.maxRisk(),
            hop1.depth(),
            hop1.maxDepth(),
            hop1.expiresAt(),
            hop1.parentId(),
            hop1.digest());
    assertThatThrownBy(() -> Mandates.verifyChain(List.of(root, widened)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest mismatch");

    Mandate reDigested =
        new Mandate(
            hop1.id(),
            hop1.issuer(),
            hop1.subject(),
            hop1.principal(),
            Set.of("jira.*", "slack.post"),
            RiskLabel.CRITICAL,
            hop1.depth(),
            hop1.maxDepth(),
            hop1.expiresAt(),
            hop1.parentId(),
            "pending");
    Mandate forged =
        new Mandate(
            reDigested.id(),
            reDigested.issuer(),
            reDigested.subject(),
            reDigested.principal(),
            reDigested.allowedTools(),
            reDigested.maxRisk(),
            reDigested.depth(),
            reDigested.maxDepth(),
            reDigested.expiresAt(),
            reDigested.parentId(),
            reDigested.expectedDigest(root.digest()));
    assertThatThrownBy(() -> Mandates.verifyChain(List.of(root, forged)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CRITICAL");
  }

  @Test
  void verifyChainDetectsBrokenParentLink() {
    Mandate root = root();
    Mandate other = root();
    Mandate hop1 = other.derive("a", Set.of("jira.*"), RiskLabel.MEDIUM, EXPIRY);
    assertThatThrownBy(() -> Mandates.verifyChain(List.of(root, hop1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("names parent");
    assertThatThrownBy(() -> Mandates.verifyChain(List.of(hop1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("root");
  }

  @Test
  void encodeDecodeRoundTripsOddCharacters() {
    Mandate root =
        Mandate.root("iss;ue=r&", "sub ject/ü")
            .id("id;with=odd,chars %")
            .principal("alice+bob@example.com; x=y")
            .allowTools("tool,with,commas", "glob;*=", "plain")
            .maxRisk(RiskLabel.LOW)
            .maxDepth(3)
            .expiresAt(Instant.parse("2031-06-15T12:34:56.789Z"))
            .build();
    Mandate child =
        root.derive("child;=,", Set.of("plain", "glob;*="), RiskLabel.LOW, root.expiresAt());

    String encoded = child.encode();
    assertThat(encoded).doesNotContain("\n").doesNotContain("\"");
    Mandate decoded = Mandate.decode(encoded);

    assertThat(decoded).isEqualTo(child);
    assertThat(Mandate.decode(root.encode())).isEqualTo(root);
    assertThat(Mandates.verifyChain(List.of(Mandate.decode(root.encode()), decoded)))
        .isEqualTo(child);
  }

  @Test
  void decodeRejectsGarbage() {
    assertThatThrownBy(() -> Mandate.decode("not a mandate"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Mandate.decode("id=a;iss=b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lacks");
  }

  @Test
  void attachAndFromTravelViaRunContextAttribute() {
    Mandate root = root();
    RunContext plain = RunContext.builder().principal("alice").attribute("k", "v").build();
    assertThat(Mandates.from(plain)).isEmpty();

    RunContext carrying = Mandates.attach(plain, root);
    assertThat(carrying.runId()).isEqualTo(plain.runId());
    assertThat(carrying.attributes()).containsEntry("k", "v").containsKey(Mandates.ATTRIBUTE);
    assertThat(Mandates.from(carrying)).contains(root);
    assertThat(plain.attributes()).doesNotContainKey(Mandates.ATTRIBUTE);
  }

  @Test
  void canonicalFormIsInjectiveAcrossFieldBoundaries() {
    Instant exp = Instant.parse("2030-01-01T00:00:00Z");
    Mandate a =
        Mandate.root("iss", "sub")
            .principal("p,tools=x")
            .allowTools("y")
            .expiresAt(exp)
            .id("m")
            .build();
    Mandate b =
        Mandate.root("iss", "sub")
            .principal("p")
            .allowTools("x,tools=y")
            .expiresAt(exp)
            .id("m")
            .build();
    assertThat(a.digest()).isNotEqualTo(b.digest());
    Mandate c = Mandate.root("iss", "sub").allowTools("a,b").expiresAt(exp).id("m").build();
    Mandate d = Mandate.root("iss", "sub").allowTools("a", "b").expiresAt(exp).id("m").build();
    assertThat(c.digest()).isNotEqualTo(d.digest());
    // Control characters and newlines in a field are refused outright.
    assertThatThrownBy(() -> Mandate.root("iss", "sub\nx").allowTools("a").expiresAt(exp).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decodeRejectsDuplicateAndUnknownKeysAndAllowToolsTakesDuplicates() {
    Instant exp = Instant.parse("2030-01-01T00:00:00Z");
    Mandate root = Mandate.root("iss", "sub").allowTools("crm.*", "crm.*").expiresAt(exp).build();
    assertThat(root.allowedTools()).containsExactly("crm.*");
    String encoded = root.encode();
    assertThatThrownBy(() -> Mandate.decode(encoded + ";tools=*"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
    assertThatThrownBy(() -> Mandate.decode(encoded + ";extra=1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown");
    assertThat(root.isSelfConsistent()).isTrue();
  }

  @Test
  void globMatchingIsLinearOnHostilePatterns() {
    Instant exp = Instant.parse("2030-01-01T00:00:00Z");
    Mandate hostile =
        Mandate.root("iss", "sub")
            .allowTools("*a*a*a*a*a*a*a*a*a*a*a*a*a*a*c")
            .expiresAt(exp)
            .build();
    long start = System.nanoTime();
    assertThat(hostile.permits("a".repeat(60))).isFalse();
    assertThat(hostile.permits("a".repeat(60) + "c")).isTrue();
    assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
    Mandate m =
        Mandate.root("iss", "sub").allowTools("crm.*", "jira.issue.*").expiresAt(exp).build();
    assertThat(m.permits("crm.read")).isTrue();
    assertThat(m.permits("crm")).isFalse();
    assertThat(m.permits("jira.issue.create")).isTrue();
    assertThat(m.permits("jira.user")).isFalse();
  }
}
