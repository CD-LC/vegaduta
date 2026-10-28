package io.vegaduta.agent.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.mandate.Mandate;
import io.vegaduta.agent.api.mandate.Mandates;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.core.hook.JsonLite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Cross-SDK fixtures in {@code sdk/testdata/cross-sdk}: a chain (and a mandate chain) written by
 * the Python SDK must verify here, and the one written here must verify in Python
 * ({@code sdk/python-agent/tests/test_cross_sdk.py}). The Python chain is built from the same
 * source events as {@link #pythonSourceEvents()}, so besides verifying, this test asserts the Java
 * writer reproduces Python's hashes and HMAC signatures byte for byte.
 *
 * <p>Regenerate the Java half with {@code -Dvegaduta.fixtures.regenerate=true} (see {@link
 * #regenerateJavaFixtures()}). The file formats are described in the Python generator,
 * {@code sdk/python-agent/tests/cross_sdk_fixtures.py}.
 */
class CrossSdkFixtureTest {

  private static final byte[] KEY =
      "cross-sdk-fixture-hmac-key-0001".getBytes(StandardCharsets.UTF_8);
  private static final HmacSha256Signer HMAC = new HmacSha256Signer(KEY);
  private static final String GENESIS = HashChainedAuditSink.GENESIS;

  // ---- the tests -------------------------------------------------------------------------

  @Test
  void chainWrittenByPythonVerifiesInJava() throws IOException {
    List<AuditEvent> events = readChain("python-chain.jsonl");
    assertThat(events).hasSize(6);

    ChainVerification unsigned = HashChainedAuditSink.verify(events, Optional.empty());
    assertThat(unsigned.ok()).as(unsigned.message()).isTrue();

    ChainVerification signed = HashChainedAuditSink.verify(events, Optional.of(HMAC));
    assertThat(signed.ok()).as(signed.message()).isTrue();
    assertThat(signed.signed()).isTrue();
    assertThat(signed.eventsChecked()).isEqualTo(6);

    ChainVerification anchored =
        HashChainedAuditSink.verify(events, Optional.of(HMAC), anchors("python-chain.anchors.json"));
    assertThat(anchored.ok()).as(anchored.message()).isTrue();
  }

  @Test
  void javaWriterReproducesPythonHashesAndSignaturesExactly() throws IOException {
    List<AuditEvent> fromPython = readChain("python-chain.jsonl");
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink sink = new HashChainedAuditSink(store, HMAC);
    pythonSourceEvents().forEach(sink::emit);
    List<AuditEvent> fromJava = store.events();

    assertThat(fromJava).hasSameSizeAs(fromPython);
    for (int i = 0; i < fromJava.size(); i++) {
      Map<String, String> java = fromJava.get(i).attributes();
      Map<String, String> python = fromPython.get(i).attributes();
      for (String key :
          List.of(
              HashChainedAuditSink.SEQ,
              HashChainedAuditSink.PREV,
              HashChainedAuditSink.HASH,
              HashChainedAuditSink.ALG,
              HashChainedAuditSink.SIG)) {
        assertThat(java.get(key)).as("event %d %s", i + 1, key).isEqualTo(python.get(key));
      }
      assertThat(HashChainedAuditSink.canonical(fromJava.get(i)))
          .isEqualTo(HashChainedAuditSink.canonical(fromPython.get(i)));
    }
  }

  @Test
  void chainWrittenByJavaVerifiesInJavaAndMatchesItsAnchors() throws IOException {
    List<AuditEvent> events = readChain("java-chain.jsonl");
    assertThat(events).isNotEmpty();
    ChainVerification v =
        HashChainedAuditSink.verify(events, Optional.of(HMAC), anchors("java-chain.anchors.json"));
    assertThat(v.ok()).as(v.message()).isTrue();
    assertThat(v.signed()).isTrue();
  }

  @Test
  void anchorsOnBothFixturesDetectTruncationThatTheLogAloneCannotShow() throws IOException {
    for (String name : List.of("python-chain", "java-chain")) {
      List<AuditEvent> all = readChain(name + ".jsonl");
      ChainAnchors anchors = anchors(name + ".anchors.json");
      int n = all.size();

      List<AuditEvent> noHead = all.subList(1, n); // prefix removed
      assertThat(HashChainedAuditSink.verify(noHead, Optional.of(HMAC)).ok())
          .as("%s: a suffix verifies on its own (that is the gap)", name)
          .isTrue();
      ChainVerification prefix = HashChainedAuditSink.verify(noHead, Optional.of(HMAC), anchors);
      assertThat(prefix.ok()).as(name).isFalse();
      assertThat(prefix.message()).contains("expectedFirstSeq");

      List<AuditEvent> noTail = all.subList(0, n - 1); // suffix removed
      assertThat(HashChainedAuditSink.verify(noTail, Optional.of(HMAC)).ok()).isTrue();
      ChainVerification suffix = HashChainedAuditSink.verify(noTail, Optional.of(HMAC), anchors);
      assertThat(suffix.ok()).as(name).isFalse();
      assertThat(suffix.message()).contains("expectedLastSeq");

      // only the hash anchor: same length, different last event
      ChainVerification headOnly =
          HashChainedAuditSink.verify(
              noTail, Optional.of(HMAC), ChainAnchors.none().withHeadHash(anchors.headHash()));
      assertThat(headOnly.ok()).as(name).isFalse();
      assertThat(headOnly.message()).contains("expectedHeadHash");

      ChainVerification emptied =
          HashChainedAuditSink.verify(List.of(), Optional.empty(), anchors);
      assertThat(emptied.ok()).isFalse();
      assertThat(emptied.message()).contains("anchor");
    }
  }

  @Test
  void bundleExportsTheSameAnchorFieldsPythonDoes() throws IOException {
    for (String name : List.of("python-chain", "java-chain")) {
      ChainAnchors a = anchors(name + ".anchors.json");
      EvidenceBundle bundle =
          EvidenceBundle.builder()
              .events(readChain(name + ".jsonl"))
              .verifier(HMAC)
              .anchors(a)
              .generatedAt(Instant.parse("2026-02-01T00:00:00Z"))
              .build();
      assertThat(bundle.chainVerification().ok()).isTrue();
      assertThat(bundle.firstSeq()).hasValue(a.firstSeq());
      assertThat(bundle.firstPrev()).hasValue(a.firstPrev());
      assertThat(bundle.lastSeq()).hasValue(a.lastSeq());
      assertThat(bundle.headHash()).hasValue(a.headHash());

      EuAiActEvidenceExporter exporter = new EuAiActEvidenceExporter(bundle);
      String integrity =
          exporter
              .toJsonLines()
              .lines()
              .filter(l -> l.contains("\"section\":\"art12.integrity\""))
              .findFirst()
              .orElseThrow();
      assertThat(integrity)
          .contains("\"firstSeq\":" + a.firstSeq())
          .contains("\"firstPrev\":\"" + a.firstPrev() + "\"")
          .contains("\"lastSeq\":" + a.lastSeq())
          .contains("\"headHash\":\"" + a.headHash() + "\"");
      assertThat(exporter.toMarkdown())
          .contains("Head: seq " + a.lastSeq() + ", hash `" + a.headHash() + "`");
    }
  }

  @Test
  void mandateChainsFromBothSdksVerifyInJava() throws IOException {
    for (String file : List.of("python-mandates.txt", "java-mandates.txt")) {
      List<Mandate> chain = new ArrayList<>();
      for (String line : Files.readAllLines(dir().resolve(file), StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          chain.add(Mandate.decode(line));
        }
      }
      assertThat(chain).as(file).hasSize(2);
      Mandate leaf = Mandates.verifyChain(chain);
      assertThat(leaf.depth()).isEqualTo(1);
      assertThat(chain.get(0).isSelfConsistent()).isTrue();
      // re-encoding what was decoded is byte-identical: one canonical text per mandate
      for (int i = 0; i < chain.size(); i++) {
        assertThat(chain.get(i).encode())
            .isEqualTo(
                Files.readAllLines(dir().resolve(file), StandardCharsets.UTF_8).get(i));
      }
    }
  }

  @Test
  void javaBuildsThePythonRootMandateWithTheSameDigest() throws IOException {
    String pythonRoot =
        Files.readAllLines(dir().resolve("python-mandates.txt"), StandardCharsets.UTF_8).get(0);
    Mandate root =
        Mandate.root("root-authority", "orchestrator")
            .id("00000000-0000-4000-8000-0000000000a1")
            .principal("alice@example.com")
            .allowTools("pay_*", "read_*", "send_email")
            .maxRisk(RiskLabel.HIGH)
            .maxDepth(3)
            .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
            .build();
    assertThat(root.encode()).isEqualTo(pythonRoot);
  }

  // ---- regeneration (explicit, never part of a normal run) -----------------------------------

  /**
   * Writes {@code java-chain.jsonl}, {@code java-chain.anchors.json} and {@code
   * java-mandates.txt}. The Java chain deliberately carries different events than the Python one so
   * the two files are independent witnesses.
   */
  @Test
  @EnabledIfSystemProperty(named = "vegaduta.fixtures.regenerate", matches = "true")
  void regenerateJavaFixtures() throws IOException {
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink sink = new HashChainedAuditSink(store, HMAC);
    javaSourceEvents().forEach(sink::emit);
    List<AuditEvent> events = store.events();

    StringBuilder jsonl = new StringBuilder();
    for (AuditEvent e : events) {
      jsonl.append(JsonLite.writeObject(eventToLine(e))).append('\n');
    }
    Files.writeString(dir().resolve("java-chain.jsonl"), jsonl, StandardCharsets.UTF_8);

    EvidenceBundle bundle = EvidenceBundle.of(events);
    Map<String, String> anchors = new LinkedHashMap<>();
    anchors.put("firstSeq", Long.toString(bundle.firstSeq().orElseThrow()));
    anchors.put("firstPrev", bundle.firstPrev().orElseThrow());
    anchors.put("lastSeq", Long.toString(bundle.lastSeq().orElseThrow()));
    anchors.put("headHash", bundle.headHash().orElseThrow());
    Files.writeString(
        dir().resolve("java-chain.anchors.json"),
        "{\n"
            + String.join(
                ",\n",
                anchors.entrySet().stream()
                    .map(en -> "  \"" + en.getKey() + "\": \"" + en.getValue() + "\"")
                    .toList())
            + "\n}\n",
        StandardCharsets.UTF_8);

    Mandate root =
        Mandate.root("java-root-authority", "coordinator")
            .id("00000000-0000-4000-8000-0000000000b1")
            .principal("carol@example.com")
            .allowTools("refund_*", "lookup", "ünïcode tool/α")
            .maxRisk(RiskLabel.CRITICAL)
            .maxDepth(2)
            .expiresAt(Instant.parse("2031-03-04T05:06:07.123456Z"))
            .build();
    Mandate child =
        root.derive(
            "refunder",
            Set.of("refund_small", "lookup"),
            RiskLabel.LOW,
            Instant.parse("2030-12-31T23:59:59Z"));
    Files.writeString(
        dir().resolve("java-mandates.txt"),
        root.encode() + "\n" + child.encode() + "\n",
        StandardCharsets.UTF_8);
  }

  // ---- fixture reading -------------------------------------------------------------------------

  private static Path dir() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null) {
      Path candidate = p.resolve("testdata").resolve("cross-sdk");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("sdk/testdata/cross-sdk not found above " + Path.of("").toAbsolutePath());
  }

  private static List<AuditEvent> readChain(String file) throws IOException {
    List<AuditEvent> events = new ArrayList<>();
    for (String raw : Files.readAllLines(dir().resolve(file), StandardCharsets.UTF_8)) {
      if (!raw.isBlank()) {
        events.add(lineToEvent(JsonLite.readFlatObject(raw)));
      }
    }
    return events;
  }

  private static ChainAnchors anchors(String file) throws IOException {
    Map<String, String> m =
        JsonLite.readFlatObject(Files.readString(dir().resolve(file), StandardCharsets.UTF_8));
    return new ChainAnchors(
        Long.parseLong(m.get("firstSeq")), m.get("firstPrev"), Long.parseLong(m.get("lastSeq")), m.get("headHash"));
  }

  private static AuditEvent lineToEvent(Map<String, String> line) {
    RunContext ctx =
        RunContext.builder()
            .runId(line.get("runId"))
            .tenantId(line.get("tenantId"))
            .agentId(line.get("agentId"))
            .sessionId(line.get("sessionId"))
            .correlationId(line.get("correlationId"))
            .build();
    Set<AsiRisk> risks = EnumSet.noneOf(AsiRisk.class);
    String riskText = line.get("risks");
    if (riskText != null && !riskText.isEmpty()) {
      for (String r : riskText.split(",")) {
        risks.add(AsiRisk.valueOf(r));
      }
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    line.forEach(
        (k, v) -> {
          if (k.startsWith("attr.")) {
            attributes.put(k.substring("attr.".length()), v);
          }
        });
    return new AuditEvent(
        UUID.fromString(line.get("id")),
        Instant.parse(line.get("createdAt")),
        line.get("actorId"),
        line.get("actionType"),
        line.get("targetType"),
        line.get("targetId"),
        AuditOutcome.valueOf(line.get("outcome")),
        line.get("detail"),
        ctx,
        risks,
        attributes);
  }

  private static Map<String, String> eventToLine(AuditEvent e) {
    Map<String, String> line = new LinkedHashMap<>();
    line.put("id", e.id().toString());
    line.put("createdAt", e.createdAt().toString());
    line.put("actorId", e.actorId());
    line.put("actionType", e.actionType());
    line.put("targetType", e.targetType());
    line.put("targetId", e.targetId());
    line.put("outcome", e.outcome().name());
    line.put("runId", e.context().runId());
    if (e.detail() != null) {
      line.put("detail", e.detail());
    }
    putIfPresent(line, "tenantId", e.context().tenantId());
    putIfPresent(line, "agentId", e.context().agentId());
    putIfPresent(line, "sessionId", e.context().sessionId());
    putIfPresent(line, "correlationId", e.context().correlationId());
    if (!e.risks().isEmpty()) {
      line.put("risks", String.join(",", new java.util.TreeSet<>(e.risks().stream().map(AsiRisk::id).toList())));
    }
    e.attributes().forEach((k, v) -> line.put("attr." + k, v));
    return line;
  }

  private static void putIfPresent(Map<String, String> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  // ---- source events -----------------------------------------------------------------------------

  private static AuditEvent event(
      long n,
      Instant at,
      RunContext ctx,
      String action,
      String targetType,
      String targetId,
      AuditOutcome outcome,
      String actor,
      String detail,
      Set<AsiRisk> risks,
      Map<String, String> attributes) {
    return new AuditEvent(
        UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n)),
        at,
        actor,
        action,
        targetType,
        targetId,
        outcome,
        detail,
        ctx,
        risks,
        attributes);
  }

  /** Mirrors {@code source_events()} in sdk/python-agent/tests/cross_sdk_fixtures.py. */
  private static List<AuditEvent> pythonSourceEvents() {
    RunContext full =
        RunContext.builder()
            .runId("run-1")
            .tenantId("acme")
            .agentId("payer")
            .sessionId("s-1")
            .correlationId("corr-9")
            .build();
    RunContext bare = RunContext.builder().runId("run-2").build();
    Map<String, String> a1 = new LinkedHashMap<>();
    a1.put("risk", "HIGH");
    a1.put("callId", "c1");
    a1.put("actionType", "shadow");
    Map<String, String> a3 = new LinkedHashMap<>();
    a3.put("status", "APPROVED");
    a3.put("chain.fake", "kept");
    a3.put("attr.x", "nested-looking");
    Map<String, String> a6 = new LinkedHashMap<>();
    a6.put("callId", "c6");
    a6.put("approvalId", "a-1");
    return List.of(
        event(
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            full,
            "tool.call",
            "tool",
            "pay_invoice",
            AuditOutcome.SUCCESS,
            "alice",
            "paid \"INV-1\" \\ path\nline2\ttab \u0001 café ☕ 😀",
            EnumSet.of(AsiRisk.ASI02, AsiRisk.ASI09),
            a1),
        event(
            2,
            Instant.parse("2026-01-01T00:00:01.250Z"),
            bare,
            "approval.requested",
            "approval",
            "a-1",
            AuditOutcome.PENDING_APPROVAL,
            "agent",
            null,
            Set.of(),
            Map.of()),
        event(
            3,
            Instant.parse("2026-01-01T00:00:02.123456Z"),
            full,
            "approval.decided",
            "approval",
            "a-1",
            AuditOutcome.APPROVED,
            "bob",
            "approved by bob",
            EnumSet.of(AsiRisk.ASI09),
            a3),
        event(
            4,
            Instant.parse("2026-01-01T00:00:03.007Z"),
            full,
            "tool.call",
            "tool",
            "send_email",
            AuditOutcome.DENIED,
            "agent",
            "",
            Set.of(),
            Map.of()),
        event(
            5,
            Instant.parse("2026-01-01T00:00:04Z"),
            bare,
            "halt.engaged",
            "halt",
            "global",
            AuditOutcome.WARNING,
            "ops",
            "stop — 日本語",
            Set.of(),
            Map.of()),
        event(
            6,
            Instant.parse("2026-01-01T00:00:05.999999Z"),
            full,
            "tool.call",
            "tool",
            "pay_invoice",
            AuditOutcome.SUCCESS,
            "alice",
            "last",
            EnumSet.of(AsiRisk.ASI02),
            a6));
  }

  /** Different events from the Python chain: the Java file is an independent witness. */
  private static List<AuditEvent> javaSourceEvents() {
    RunContext ctx =
        RunContext.builder()
            .runId("jrun-7")
            .tenantId("globex")
            .agentId("refunder")
            .correlationId("c-üñî")
            .build();
    TreeMap<String, String> attrs = new TreeMap<>();
    attrs.put("callId", "j-1");
    attrs.put("note", "tab\there \"quoted\" back\\slash 🚀");
    return List.of(
        event(
            101,
            Instant.parse("2026-03-10T08:00:00Z"),
            ctx,
            "tool.call",
            "tool",
            "refund_small",
            AuditOutcome.AUTO_APPROVED,
            "dave",
            "refund 12.50 €",
            EnumSet.of(AsiRisk.ASI02),
            attrs),
        event(
            102,
            Instant.parse("2026-03-10T08:00:00.5Z"),
            ctx,
            "approval.requested",
            "approval",
            "j-a1",
            AuditOutcome.PENDING_APPROVAL,
            "dave",
            "refund 900",
            EnumSet.of(AsiRisk.ASI09),
            Map.of("actionType", "tool:refund_big")),
        event(
            103,
            Instant.parse("2026-03-10T08:00:01.000042Z"),
            ctx,
            "approval.decided",
            "approval",
            "j-a1",
            AuditOutcome.REJECTED,
            "erin",
            "too large\nsecond line",
            EnumSet.of(AsiRisk.ASI09),
            Map.of("status", "REJECTED")),
        event(
            104,
            Instant.parse("2026-03-10T08:00:02Z"),
            ctx,
            "halt.released",
            "halt",
            "global",
            AuditOutcome.SUCCESS,
            "ops",
            null,
            Set.of(),
            Map.of()),
        event(
            105,
            Instant.parse("2026-03-10T08:00:03Z"),
            ctx,
            "tool.call",
            "tool",
            "lookup",
            AuditOutcome.SUCCESS,
            "dave",
            "ok",
            Set.of(),
            Map.of("callId", "j-5")));
  }
}
