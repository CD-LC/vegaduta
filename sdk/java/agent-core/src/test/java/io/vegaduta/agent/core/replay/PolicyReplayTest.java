package io.vegaduta.agent.core.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.gate.PolicyToolDispatchGate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyReplayTest {

  private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
  private static final RunContext CTX = RunContext.ofRun("run-1");

  private static final ToolClassification SEARCH = ToolClassification.readOnly("search");
  private static final ToolClassification SEND_EMAIL =
      ToolClassification.builder("send_email").risk(RiskLabel.MEDIUM).sideEffecting(true).build();

  private static AuditEvent toolCall(
      String callId, String tool, AuditOutcome outcome, RiskLabel risk, Instant at) {
    return AuditEvent.builder(RecordedCall.ACTION_TYPE, "tool", tool)
        .context(CTX)
        .outcome(outcome)
        .at(at)
        .attribute("callId", callId)
        .attribute("risk", risk.name())
        .build();
  }

  /**
   * Two days of traffic: 4 searches and 4 emails, all allowed under the current (lenient) policy.
   */
  private static List<AuditEvent> lenientHistory() {
    return List.of(
        toolCall("c1", "search", AuditOutcome.SUCCESS, RiskLabel.LOW, T0),
        toolCall("c2", "send_email", AuditOutcome.SUCCESS, RiskLabel.MEDIUM, T0.plusSeconds(3600)),
        toolCall("c3", "send_email", AuditOutcome.SUCCESS, RiskLabel.MEDIUM, T0.plusSeconds(7200)),
        toolCall("c4", "search", AuditOutcome.SUCCESS, RiskLabel.LOW, T0.plusSeconds(86_400)),
        toolCall(
            "c5", "send_email", AuditOutcome.SUCCESS, RiskLabel.MEDIUM, T0.plusSeconds(90_000)),
        toolCall(
            "c6", "send_email", AuditOutcome.FAILURE, RiskLabel.MEDIUM, T0.plusSeconds(172_800)),
        toolCall("c7", "search", AuditOutcome.SUCCESS, RiskLabel.LOW, T0.plusSeconds(172_800)),
        AuditEvent.builder("approval.decided", "approval", "a1").context(CTX).at(T0).build());
  }

  @Test
  void stricterPolicyEscalatesCallsThatActuallySucceeded() {
    GovernancePolicy stricter =
        GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build();

    ReplayReport report =
        PolicyReplay.of(stricter, List.of(SEARCH, SEND_EMAIL)).replay(lenientHistory());

    assertThat(report.totals().calls()).isEqualTo(7);
    assertThat(report.totals().wouldAllow()).isEqualTo(3);
    assertThat(report.totals().wouldRequireApproval()).isEqualTo(4);
    assertThat(report.totals().wouldDeny()).isZero();
    assertThat(report.deltas().newlyEscalated()).isEqualTo(4);
    assertThat(report.deltas().newlyBlocked()).isZero();
    assertThat(report.deltas().relaxed()).isZero();
    assertThat(report.deltas().unchanged()).isEqualTo(3);
    // 4 new reviews over exactly 2 days.
    assertThat(report.reviewLoad().newReviews()).isEqualTo(4);
    assertThat(report.reviewLoad().spanDays()).isEqualTo(2.0);
    assertThat(report.reviewLoad().perDay()).isEqualTo(2.0);
    assertThat(report.examples().get(ChangeKind.NEWLY_ESCALATED))
        .containsExactly("c2", "c3", "c5", "c6");
    assertThat(report.perTool())
        .extracting(ReplayReport.ToolRow::toolName)
        .containsExactly("search", "send_email");
    ReplayReport.ToolRow email = report.perTool().get(1);
    assertThat(email.calls()).isEqualTo(4);
    assertThat(email.newlyEscalated()).isEqualTo(4);
    assertThat(report.rows().get(1).replayedReason())
        .isEqualTo(PolicyToolDispatchGate.REASON_RISK_THRESHOLD);
  }

  @Test
  void strictModeBlocksUnclassifiedToolsAndCountsThemAsNewlyBlocked() {
    GovernancePolicy strict = GovernancePolicy.builder().mode(GovernanceMode.STRICT).build();

    // Only search is classified; send_email becomes unclassified under the candidate.
    ReplayReport report = PolicyReplay.of(strict, List.of(SEARCH)).replay(lenientHistory());

    assertThat(report.totals().wouldDeny()).isEqualTo(4);
    assertThat(report.deltas().newlyBlocked()).isEqualTo(4);
    assertThat(report.examples().get(ChangeKind.NEWLY_BLOCKED)).contains("c2", "c6");
    assertThat(report.rows().get(1).replayedReason())
        .isEqualTo(PolicyToolDispatchGate.REASON_UNCLASSIFIED);
  }

  @Test
  void relaxedPolicyReportsCallsThatWereEscalatedOrDeniedAsRelaxed() {
    List<AuditEvent> strictHistory =
        List.of(
            toolCall("d1", "send_email", AuditOutcome.PENDING_APPROVAL, RiskLabel.MEDIUM, T0),
            toolCall("d2", "send_email", AuditOutcome.DENIED, RiskLabel.MEDIUM, T0.plusSeconds(60)),
            toolCall("d3", "search", AuditOutcome.SUCCESS, RiskLabel.LOW, T0.plusSeconds(120)));
    GovernancePolicy relaxed =
        GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.CRITICAL).build();

    ReplayReport report =
        PolicyReplay.of(relaxed, List.of(SEARCH, SEND_EMAIL)).replay(strictHistory);

    assertThat(report.totals().wouldAllow()).isEqualTo(3);
    assertThat(report.deltas().relaxed()).isEqualTo(2);
    assertThat(report.deltas().newlyEscalated()).isZero();
    assertThat(report.examples().get(ChangeKind.RELAXED)).containsExactly("d1", "d2");
    assertThat(report.reviewLoad().newReviews()).isZero();
    // Sub-day span is clamped to one day.
    assertThat(report.reviewLoad().spanDays()).isEqualTo(1.0);
  }

  @Test
  void denyListOnTheCandidateGateIsHonoured() {
    GovernancePolicy same = GovernancePolicy.defaults();

    ReplayReport report =
        PolicyReplay.of(same, List.of(SEARCH, SEND_EMAIL))
            .deny("send_email")
            .replay(lenientHistory());

    assertThat(report.deltas().newlyBlocked()).isEqualTo(4);
    assertThat(report.rows().get(1).replayedReason())
        .isEqualTo(PolicyToolDispatchGate.REASON_DENY_LIST);
  }

  @Test
  void repeatedEventsForOneCallFoldIntoTheMostSevereActualVerdict() {
    List<AuditEvent> events =
        List.of(
            toolCall("x1", "send_email", AuditOutcome.WARNING, RiskLabel.MEDIUM, T0),
            toolCall(
                "x1",
                "send_email",
                AuditOutcome.PENDING_APPROVAL,
                RiskLabel.MEDIUM,
                T0.plusSeconds(1)),
            toolCall(
                "x1", "send_email", AuditOutcome.SUCCESS, RiskLabel.MEDIUM, T0.plusSeconds(3600)));

    List<RecordedCall> calls = RecordedCall.from(events);

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).actual()).isEqualTo(Verdict.Outcome.REQUIRE_APPROVAL);
    assertThat(calls.get(0).actualOutcome()).isEqualTo(AuditOutcome.PENDING_APPROVAL);
    assertThat(calls.get(0).recordedAt()).isEqualTo(T0);
  }

  @Test
  void emptyHistoryProducesAnEmptyReport() {
    ReplayReport report =
        PolicyReplay.of(GovernancePolicy.defaults(), List.of(SEARCH)).replay(List.of());

    assertThat(report.totals().calls()).isZero();
    assertThat(report.perTool()).isEmpty();
    assertThat(report.from()).isNull();
    assertThat(report.reviewLoad().perDay()).isZero();
    assertThat(report.toMarkdown()).contains("Calls replayed: 0");
  }

  @Test
  void maxExamplesCapsTheExampleList() {
    GovernancePolicy stricter =
        GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build();

    ReplayReport report =
        PolicyReplay.of(stricter, List.of(SEARCH, SEND_EMAIL))
            .maxExamples(2)
            .replay(lenientHistory());

    assertThat(report.examples().get(ChangeKind.NEWLY_ESCALATED)).containsExactly("c2", "c3");
    assertThat(report.deltas().newlyEscalated()).isEqualTo(4);
  }

  @Test
  void markdownAndJsonLinesRenderTheReport() {
    GovernancePolicy stricter =
        GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build();
    ReplayReport report =
        PolicyReplay.of(stricter, List.of(SEARCH, SEND_EMAIL)).replay(lenientHistory());

    String md = report.toMarkdown();
    assertThat(md)
        .contains("# Policy replay")
        .contains("approvalRequiredFrom=MEDIUM")
        .contains("| 3 | 4 | 0 |")
        .contains("4 new human reviews over 2.0 day(s): 2.00 per day.")
        .contains("| send_email | 4 | 0 | 4 | 0 | 0 | 4 | 0 |")
        .contains("- newly escalated: c2, c3, c5, c6");

    String[] lines = report.toJsonLines().split("\n");
    // summary + 2 tools + 7 calls
    assertThat(lines).hasSize(10);
    assertThat(lines[0])
        .startsWith("{\"type\":\"summary\"")
        .contains("\"newlyEscalated\":\"4\"")
        .contains("\"reviewsPerDay\":\"2.0000\"");
    assertThat(lines[2]).contains("\"type\":\"tool\"").contains("\"tool\":\"send_email\"");
    assertThat(lines[4])
        .contains("\"callId\":\"c2\"")
        .contains("\"actual\":\"ALLOW\"")
        .contains("\"replayed\":\"REQUIRE_APPROVAL\"")
        .contains("\"change\":\"NEWLY_ESCALATED\"");
  }

  @Test
  void policyDiffListsOnlyTheKnobsThatChanged() {
    GovernancePolicy current = GovernancePolicy.defaults();
    GovernancePolicy candidate =
        current.toBuilder()
            .approvalRequiredFrom(RiskLabel.MEDIUM)
            .mode(GovernanceMode.STRICT)
            .maxToolCallsPerRun(20)
            .build();

    PolicyDiff diff = PolicyDiff.of(current, candidate);

    assertThat(diff.changes())
        .extracting(PolicyDiff.Change::knob)
        .containsExactly("mode", "approvalRequiredFrom", "maxToolCallsPerRun");
    assertThat(diff.affectsGate()).isTrue();
    assertThat(diff.toText())
        .isEqualTo(
            "mode: WARN -> STRICT\n"
                + "approvalRequiredFrom: HIGH -> MEDIUM\n"
                + "maxToolCallsPerRun: 50 -> 20");
    assertThat(diff.toMarkdown()).contains("| approvalRequiredFrom | HIGH | MEDIUM |");

    PolicyDiff none = PolicyDiff.of(current, current);
    assertThat(none.isEmpty()).isTrue();
    assertThat(none.affectsGate()).isFalse();
    assertThat(none.toText()).isEqualTo("no changes");

    PolicyDiff budgetOnly = PolicyDiff.of(current, current.toBuilder().maxTokensPerRun(9).build());
    assertThat(budgetOnly.affectsGate()).isFalse();
  }

  @Test
  void approvedThenExecutedCallIsOneRowAndNonGateDenialsAreNotGateVerdicts() {
    GovernancePolicy same =
        GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build();
    List<AuditEvent> events =
        List.of(
            AuditEvent.builder(RecordedCall.ACTION_TYPE, "tool", "send_email")
                .context(CTX)
                .outcome(AuditOutcome.PENDING_APPROVAL)
                .at(T0)
                .attribute("callId", "k1")
                .attribute("risk", "MEDIUM")
                .attribute("stage", "gate")
                .attribute("approvalId", "ap-1")
                .build(),
            // The post-approval execution is audited under a fresh call id.
            AuditEvent.builder(RecordedCall.ACTION_TYPE, "tool", "send_email")
                .context(CTX)
                .outcome(AuditOutcome.SUCCESS)
                .at(T0.plusSeconds(60))
                .attribute("callId", "random-uuid")
                .attribute("risk", "MEDIUM")
                .attribute("stage", "approval")
                .attribute("approvalId", "ap-1")
                .build(),
            // Output-guardrail block after the gate ALLOWed: not a gate denial.
            AuditEvent.builder(RecordedCall.ACTION_TYPE, "tool", "search")
                .context(CTX)
                .outcome(AuditOutcome.DENIED)
                .detail("output guardrail: [pii]")
                .at(T0.plusSeconds(120))
                .attribute("callId", "k2")
                .attribute("risk", "LOW")
                .attribute("stage", "output_guardrail")
                .build(),
            // Same, from an older trail without the stage attribute.
            AuditEvent.builder(RecordedCall.ACTION_TYPE, "tool", "search")
                .context(CTX)
                .outcome(AuditOutcome.DENIED)
                .detail("hook: blocked")
                .at(T0.plusSeconds(180))
                .attribute("callId", "k3")
                .attribute("risk", "LOW")
                .build(),
            // Served from the journal before the gate ran.
            AuditEvent.builder(RecordedCall.ACTION_TYPE, "tool", "search")
                .context(CTX)
                .outcome(AuditOutcome.REPLAYED)
                .at(T0.plusSeconds(240))
                .attribute("callId", "k4")
                .attribute("stage", "journal")
                .build());

    List<RecordedCall> calls = RecordedCall.from(events);
    assertThat(calls).extracting(RecordedCall::callId).containsExactly("k1");
    assertThat(calls.get(0).actual()).isEqualTo(Verdict.Outcome.REQUIRE_APPROVAL);

    ReplayReport report = PolicyReplay.of(same, List.of(SEARCH, SEND_EMAIL)).replay(events);
    assertThat(report.totals().calls()).isEqualTo(1);
    assertThat(report.deltas().newlyEscalated()).isZero();
    assertThat(report.deltas().relaxed()).isZero();
    assertThat(report.deltas().unchanged()).isEqualTo(1);
  }

  @Test
  void aTerminalOutcomeReplacesThePreliminaryWarningOfAnUnclassifiedTool() {
    List<AuditEvent> events =
        List.of(
            toolCall("c1", "mystery", AuditOutcome.WARNING, RiskLabel.MEDIUM, T0),
            toolCall("c1", "mystery", AuditOutcome.SUCCESS, RiskLabel.MEDIUM, T0.plusSeconds(1)),
            toolCall("c2", "mystery", AuditOutcome.WARNING, RiskLabel.MEDIUM, T0),
            toolCall(
                "c2",
                "mystery",
                AuditOutcome.BUDGET_EXCEEDED,
                RiskLabel.MEDIUM,
                T0.plusSeconds(1)));

    List<RecordedCall> calls = RecordedCall.from(events);

    assertThat(calls)
        .extracting(RecordedCall::actualOutcome)
        .containsExactly(AuditOutcome.SUCCESS, AuditOutcome.BUDGET_EXCEEDED);
    assertThat(calls).extracting(RecordedCall::actual).containsOnly(Verdict.Outcome.ALLOW);
  }

  @Test
  void configurationMethodsMutateOneInstanceAndMarkdownEscapesIds() {
    PolicyReplay base = PolicyReplay.of(GovernancePolicy.defaults(), List.of(SEARCH, SEND_EMAIL));
    PolicyReplay derived = base.maxExamples(1).deny("search");
    assertThat(derived).isSameAs(base);

    PolicyReplay independent =
        PolicyReplay.of(GovernancePolicy.defaults(), List.of(SEARCH, SEND_EMAIL));
    assertThat(
            independent
                .gate()
                .check(new io.vegaduta.agent.api.tool.ToolCall("x", "search", "{}"), CTX)
                .allowed())
        .isTrue();

    String md =
        base.replay(
                List.of(
                    toolCall("abc | 0 | 99", "search", AuditOutcome.SUCCESS, RiskLabel.LOW, T0)))
            .toMarkdown();
    assertThat(md).contains("abc \\| 0 \\| 99");
  }
}
