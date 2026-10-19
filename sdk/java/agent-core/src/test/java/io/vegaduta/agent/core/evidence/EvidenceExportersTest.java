package io.vegaduta.agent.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceExportersTest {

  private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
  private static final RunContext CTX =
      RunContext.builder()
          .tenantId("acme")
          .agentId("payer")
          .principal("alice")
          .runId("run-1")
          .build();
  private static final String APPROVAL = UUID.randomUUID().toString();

  private static final ToolClassification PAY =
      ToolClassification.builder("pay_invoice")
          .risk(RiskLabel.HIGH)
          .sideEffecting(true)
          .requiresApproval(true)
          .risk(AsiRisk.ASI02)
          .build();
  private static final ToolClassification LOOKUP = ToolClassification.readOnly("lookup_vendor");

  private static AuditEvent.Builder toolCall(
      String tool, AuditOutcome outcome, String detail, int second) {
    return AuditEvent.builder("tool.call", "tool", tool)
        .context(CTX)
        .outcome(outcome)
        .detail(detail)
        .attribute("callId", "c" + second)
        .attribute("risk", tool.equals("pay_invoice") ? "HIGH" : "LOW")
        .risks(tool.equals("pay_invoice") ? java.util.Set.of(AsiRisk.ASI02) : java.util.Set.of())
        .at(T0.plusSeconds(second));
  }

  /** A realistic little run: lookups, an approval round-trip, a denial, a budget stop, a halt. */
  private static InMemoryAuditSink synthetic(boolean chained) {
    InMemoryAuditSink store = new InMemoryAuditSink();
    var sink = chained ? new HashChainedAuditSink(store) : store;
    sink.emit(toolCall("lookup_vendor", AuditOutcome.SUCCESS, null, 1).build());
    sink.emit(toolCall("lookup_vendor", AuditOutcome.REPLAYED, "served from journal", 2).build());
    sink.emit(
        toolCall("pay_invoice", AuditOutcome.PENDING_APPROVAL, "approval required", 3)
            .attribute("approvalId", APPROVAL)
            .build());
    sink.emit(
        AuditEvent.builder("approval.requested", "approval", APPROVAL)
            .context(CTX)
            .outcome(AuditOutcome.PENDING_APPROVAL)
            .detail("Agent payer acting for alice wants to call 'pay_invoice'")
            .risk(AsiRisk.ASI09)
            .attribute("actionType", "tool:pay_invoice")
            .at(T0.plusSeconds(3))
            .build());
    sink.emit(
        AuditEvent.builder("approval.decided", "approval", APPROVAL)
            .context(CTX)
            .actor("bob")
            .outcome(AuditOutcome.APPROVED)
            .detail("approved by bob")
            .risk(AsiRisk.ASI09)
            .attribute("actionType", "tool:pay_invoice")
            .attribute("status", "APPROVED")
            .at(T0.plusSeconds(10))
            .build());
    sink.emit(
        toolCall("pay_invoice", AuditOutcome.SUCCESS, "executed after approval", 11)
            .attribute("approvalId", APPROVAL)
            .build());
    sink.emit(toolCall("pay_invoice", AuditOutcome.SUCCESS, null, 12).build());
    sink.emit(toolCall("pay_invoice", AuditOutcome.DENIED, "gate: amount over limit", 13).build());
    sink.emit(
        toolCall("lookup_vendor", AuditOutcome.BUDGET_EXCEEDED, "tool calls exhausted", 14)
            .build());
    sink.emit(
        toolCall("lookup_vendor", AuditOutcome.WARNING, "output redacted: [EMAIL]", 15).build());
    sink.emit(
        AuditEvent.builder("halt.run", "run", "run-1")
            .context(CTX)
            .actor("carol")
            .outcome(AuditOutcome.SUCCESS)
            .detail("operator stop")
            .risk(AsiRisk.ASI10)
            .at(T0.plusSeconds(20))
            .build());
    return store;
  }

  private static EvidenceBundle bundle(boolean chained) {
    return EvidenceBundle.builder()
        .events(synthetic(chained).events())
        .classification(PAY)
        .classification(LOOKUP)
        .generatedAt(T0.plusSeconds(3600))
        .build();
  }

  @Test
  void euAiActMarkdownHasTheThreeArticleSectionsWithEvidence() {
    String md = new EuAiActEvidenceExporter(bundle(true)).toMarkdown();

    assertThat(md).contains("## Article 12 - Record-keeping").contains("**Chain intact.**");
    assertThat(md).contains("11 events verified").contains("Retain these records");
    assertThat(md)
        .contains("| 1 | 2026-03-01T09:00:01Z | alice | tool.call | tool:lookup_vendor |");

    assertThat(md).contains("## Article 14 - Human oversight");
    assertThat(md).contains("### Approvals requested").contains("tool:pay_invoice");
    assertThat(md).contains("| APPROVED | bob |").contains("Human approvers: bob.");
    assertThat(md).contains("### Denials").contains("gate: amount over limit");
    assertThat(md).contains("### Halts").contains("| carol | halt.run | run:run-1 |");
    assertThat(md).contains("### Budget stops").contains("tool calls exhausted");
    assertThat(md).contains("Executions after human approval: 1; autonomous executions: 2.");

    assertThat(md).contains("## Article 9 - Risk management");
    assertThat(md).contains("| pay_invoice | HIGH | ASI02 | 4 |");
    assertThat(md).contains("| lookup_vendor | LOW | - | 4 |");
  }

  @Test
  void euAiActReportsUnchainedEventsAndMissingOversightHonestly() {
    EvidenceBundle unchained = bundle(false);
    String md = new EuAiActEvidenceExporter(unchained).toMarkdown();

    assertThat(unchained.chained()).isFalse();
    assertThat(md).contains("**not hash-chained**");

    EvidenceBundle empty = EvidenceBundle.builder().generatedAt(T0).build();
    String quiet = new EuAiActEvidenceExporter(empty).toMarkdown();
    assertThat(quiet)
        .contains("No action required human approval")
        .contains("No operator halt")
        .contains("No tools were classified or called");
  }

  @Test
  void euAiActJsonLinesHaveOneObjectPerLineWithSections() {
    String jsonl = new EuAiActEvidenceExporter(bundle(true)).toJsonLines();
    List<String> lines = jsonl.lines().toList();

    assertThat(lines).allSatisfy(l -> assertThat(l).startsWith("{\"section\":\"").endsWith("}"));
    assertThat(lines.get(0)).contains("\"standard\":\"EU AI Act\"").contains("\"events\":11");
    assertThat(lines.get(1)).contains("\"section\":\"art12.integrity\"").contains("\"ok\":true");
    assertThat(lines.stream().filter(l -> l.contains("\"section\":\"art12.log\"")).count())
        .isEqualTo(11);
    assertThat(jsonl)
        .contains("\"section\":\"art14.oversight\"")
        .contains("\"approvalsRequested\":1")
        .contains("\"approvers\":[\"bob\"]")
        .contains("\"halts\":1")
        .contains("\"section\":\"art9.tool\",\"tool\":\"pay_invoice\",\"risk\":\"HIGH\"");
    // detail with brackets and the agent summary quote must be escaped, not break the line
    assertThat(jsonl).contains("wants to call 'pay_invoice'");
  }

  @Test
  void owaspCoverageMarksExercisedAndNotExercisedItems() {
    OwaspAsiCoverageReport report = new OwaspAsiCoverageReport(bundle(true));
    String md = report.toMarkdown();

    assertThat(report.exercised(AsiRisk.ASI02)).isTrue();
    assertThat(report.exercised(AsiRisk.ASI01)).isFalse();
    assertThat(md).contains("| ASI01 | Agent Goal Hijack | not exercised | - | 0 |");
    assertThat(md)
        .contains("| ASI02 | Tool Misuse and Exploitation | tool.call tool:pay_invoice |")
        .contains("DENIED=1")
        .contains("PENDING_APPROVAL=1")
        .contains("SUCCESS=2");
    assertThat(md).contains("| ASI09 | Human-Agent Trust Exploitation |").contains("APPROVED=1");
    assertThat(md).contains("| ASI10 | Rogue Agents | halt.run run:run-1 |");
    assertThat(md).contains("3 of 10 items exercised");

    List<String> lines = report.toJsonLines().lines().toList();
    assertThat(lines).hasSize(10);
    assertThat(lines.get(0)).contains("\"item\":\"ASI01\"").contains("\"exercised\":false");
    assertThat(lines.get(1))
        .contains("\"item\":\"ASI02\"")
        .contains("\"exercised\":true")
        .contains("\"outcomes\":{")
        .contains("\"DENIED\":1");
  }

  @Test
  void underwritingComputesHumanApprovalShareAndStops() {
    UnderwritingReport report = new UnderwritingReport(bundle(true));
    Map<String, UnderwritingReport.ScopeSummary> summaries = report.summaries();

    assertThat(summaries).containsOnlyKeys("acme/payer");
    UnderwritingReport.ScopeSummary s = summaries.get("acme/payer");
    assertThat(s.toolsWithRisk())
        .containsEntry("pay_invoice", "HIGH")
        .containsEntry("lookup_vendor", "LOW");
    assertThat(s.toolCalls()).isEqualTo(8);
    assertThat(s.sideEffectingCalls()).isEqualTo(2);
    assertThat(s.sideEffectingHumanApproved()).isEqualTo(1);
    assertThat(s.humanApprovedShare()).isEqualTo(0.5);
    assertThat(s.autonomousExecutions()).isEqualTo(2);
    assertThat(s.denied()).isEqualTo(1);
    assertThat(s.budgetExceeded()).isEqualTo(1);
    assertThat(s.replayed()).isEqualTo(1);
    assertThat(s.redactions()).isEqualTo(1);

    String md = report.toMarkdown();
    assertThat(md).contains("## Tenant/agent acme/payer");
    assertThat(md).contains("50.0% of completed side-effecting actions (1 of 2)");
    assertThat(md).contains("completed 2 actions without a human decision");
    assertThat(md).contains("1 call denied").contains("1 stop by budget limits");
    assertThat(md).contains("1 repeated call served from the effect journal");
    assertThat(md).contains("1 tool output redacted");
    assertThat(md).contains("Records tamper-evident: hash-linked only, signatures NOT verified");

    String jsonl = report.toJsonLines();
    assertThat(jsonl)
        .contains("\"section\":\"underwriting.scope\",\"scope\":\"acme/payer\"")
        .contains("\"tools\":{\"lookup_vendor\":\"LOW\",\"pay_invoice\":\"HIGH\"}")
        .contains("\"humanApprovedShare\":0.5");
  }

  @Test
  void bundleFiltersByPeriodAndFallsBackToRiskAttributeWithoutClassification() {
    List<AuditEvent> all = synthetic(false).events();
    EvidenceBundle window =
        EvidenceBundle.builder()
            .events(all)
            .from(T0.plusSeconds(10))
            .to(T0.plusSeconds(13))
            .generatedAt(T0)
            .build();

    assertThat(window.events()).hasSize(3);
    assertThat(window.approvalsDecided()).hasSize(1);
    assertThat(window.riskLabelOf("pay_invoice")).isEqualTo("HIGH");
    assertThat(window.riskLabelOf("never_called")).isEqualTo("UNCLASSIFIED");
    assertThat(window.sideEffecting(window.toolCalls().get(0))).isTrue();
    assertThat(window.periodText()).isEqualTo("2026-03-01T09:00:10Z to 2026-03-01T09:00:13Z");
  }

  @Test
  void periodFilterDoesNotBreakTheChainAndUnchainedEventsAreCountedSeparately() {
    InMemoryAuditSink store = new InMemoryAuditSink();
    // One event written before the chained sink existed.
    store.emit(toolCall("lookup_vendor", AuditOutcome.SUCCESS, null, 0).build());
    HashChainedAuditSink sink = new HashChainedAuditSink(store);
    // seq 1 has a LATER createdAt than seq 2 (parallel callers: createdAt is taken before the lock)
    sink.emit(toolCall("lookup_vendor", AuditOutcome.SUCCESS, null, 5).build());
    sink.emit(toolCall("lookup_vendor", AuditOutcome.SUCCESS, null, 4).build());
    sink.emit(toolCall("lookup_vendor", AuditOutcome.SUCCESS, null, 6).build());

    EvidenceBundle bundle =
        EvidenceBundle.builder().events(store.events()).from(T0.plusSeconds(5)).build();

    assertThat(bundle.chained()).isTrue();
    assertThat(bundle.chainVerification().ok()).isTrue();
    assertThat(bundle.unchainedEvents()).isEqualTo(1);
    assertThat(bundle.events()).hasSize(2);
    String md = new EuAiActEvidenceExporter(bundle).toMarkdown();
    assertThat(md).contains("**Chain intact.**").contains("1 event in the log carry no chain");
  }

  @Test
  void underwritingReportDoesNotClaimVerificationForAnUnsignedChain() {
    UnderwritingReport unsigned = new UnderwritingReport(bundle(true));
    assertThat(unsigned.toMarkdown())
        .contains("Records tamper-evident: hash-linked only, signatures NOT verified")
        .doesNotContain("yes, verified");
    assertThat(unsigned.toJsonLines()).contains("\"chainSigned\":false");

    byte[] key = "k".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    HmacSha256Signer signer = new HmacSha256Signer(key);
    InMemoryAuditSink store = new InMemoryAuditSink();
    HashChainedAuditSink sink = new HashChainedAuditSink(store, signer);
    sink.emit(toolCall("pay_invoice", AuditOutcome.SUCCESS, null, 1).build());
    EvidenceBundle signed =
        EvidenceBundle.builder().events(store.events()).verifier(signer).build();
    assertThat(new UnderwritingReport(signed).toMarkdown())
        .contains("yes, hash-linked and signatures verified (HmacSHA256)");
  }

  @Test
  void jsonLogLinesKeepTopLevelFieldsAndNestAttributes() {
    String jsonl = new EuAiActEvidenceExporter(bundle(true)).toJsonLines();
    String decided =
        jsonl
            .lines()
            .filter(l -> l.contains("\"actionType\":\"approval.decided\""))
            .findFirst()
            .orElseThrow();
    assertThat(decided)
        .contains("\"attributes\":{")
        .contains("\"actionType\":\"tool:pay_invoice\"");
    // An attribute named "section" cannot duplicate the key.
    AuditEvent e =
        toolCall("lookup_vendor", AuditOutcome.SUCCESS, "x | y", 1)
            .attribute("section", "x")
            .build();
    String line = EuAiActEvidenceExporter.logLine(e).toString();
    assertThat(line.indexOf("\"section\""))
        .isEqualTo(line.lastIndexOf("\"section\":\"art12.log\""));
    assertThat(line).contains("\"attributes\":{").contains("\"section\":\"x\"");
    // Detail cells are escaped once, not twice.
    String md = new EuAiActEvidenceExporter(EvidenceBundle.of(List.of(e))).toMarkdown();
    assertThat(md).contains("x \\| y").doesNotContain("\\\\|");
  }
}
