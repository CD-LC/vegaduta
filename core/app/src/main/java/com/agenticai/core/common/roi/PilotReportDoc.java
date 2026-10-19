package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.PilotDtos.AssumptionLine;
import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import com.agenticai.core.common.roi.PilotDtos.PilotReport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The pilot report as a neutral document (headings, label/value rows, an assumptions box) that the
 * HTML and PDF renderers draw identically. All wording lives here, so both formats say the same
 * thing. A missing number prints as {@link #DASH} with the reason, never as zero.
 */
public record PilotReportDoc(
        String title,
        String subtitle,
        boolean demo,
        String demoLabel,
        List<Section> sections,
        List<AssumptionLine> assumptions,
        List<String> footnotes) {

    public static final String DASH = "—";
    public static final String WATERMARK = "EXAMPLE - DEMO DATA, NOT A CUSTOMER RESULT";

    public record Row(String label, String value, String note) {}

    public record Section(String heading, List<Row> rows, String message) {}

    public static PilotReportDoc from(PilotReport r) {
        List<Section> sections = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(r.from(), r.to()) + 1;

        // 1. What the agent handled
        if (r.conversations() == 0) {
            sections.add(
                    new Section(
                            "What the agent handled (recorded)",
                            List.of(),
                            "No conversations with a recorded outcome in this period, so no rates"
                                    + " are shown. A rate with nothing behind it would read as"
                                    + " perfect or as failed; it is neither."));
        } else {
            sections.add(
                    new Section(
                            "What the agent handled (recorded)",
                            List.of(
                                    new Row(
                                            "Conversations with a recorded outcome",
                                            String.valueOf(r.conversations()),
                                            null),
                                    new Row(
                                            "Resolved by the agent",
                                            r.resolved() + " (" + pct(r.resolvedRate()) + ")",
                                            r.includeAssumedResolutions()
                                                    ? "includes idle-closed conversations assumed resolved"
                                                    : "idle-closed conversations assumed resolved are not counted"),
                                    new Row(
                                            "Handed to a human",
                                            r.handedToHuman() + " (" + pct(r.handoverRate()) + ")",
                                            null),
                                    new Row(
                                            "Corrected by a human",
                                            r.humanCorrected()
                                                    + " ("
                                                    + pct(r.humanCorrectionRate())
                                                    + ")",
                                            null)),
                            null));
        }

        // 2. Response time against the baseline
        BaselineResponse b = r.baseline();
        List<Row> time = new ArrayList<>();
        time.add(
                new Row(
                        "Median response time per model call (recorded)",
                        r.medianResponseSeconds() == null
                                ? DASH + " (no successful calls recorded)"
                                : seconds(r.medianResponseSeconds()) + " (" + r.responseSamples() + " calls)",
                        "time the model took to answer, not the customer's end-to-end wait"));
        time.add(
                new Row(
                        "Baseline average handling time (tenant-entered)",
                        b.avgHandlingMinutes() == null
                                ? DASH + " (not entered)"
                                : PilotReportService.plain(b.avgHandlingMinutes()) + " min",
                        "a different measure from the row above; compare with care"));
        sections.add(new Section("Response time", time, null));

        // 3. Before the pilot vs during
        List<Row> before = new ArrayList<>();
        before.add(
                new Row(
                        "Monthly volume",
                        (b.monthlyVolume() == null
                                        ? "baseline " + DASH + " (not entered)"
                                        : "baseline "
                                                + PilotReportService.plain(b.monthlyVolume())
                                                + " per month (tenant-entered)")
                                + "; during the pilot "
                                + r.conversations()
                                + " conversations in "
                                + days
                                + " days (recorded)",
                        null));
        before.add(
                new Row(
                        "Missed rate",
                        b.missedRate() == null
                                ? "baseline " + DASH + " (not entered)"
                                : "baseline " + pct(b.missedRate()) + " (tenant-entered)",
                        "the platform does not record missed contacts, so there is no during figure"));
        sections.add(new Section("Before the pilot, as entered by the tenant", before, null));

        // 4. Cost
        List<Row> cost = new ArrayList<>();
        cost.add(
                new Row(
                        "Estimated model cost",
                        costText(r),
                        r.costComplete()
                                ? "from recorded token counts and the prices in the assumptions box"
                                : "lower bound: some calls have no price entered or reported no token usage"));
        cost.add(
                new Row(
                        "Estimated cost per resolved conversation",
                        r.costPerResolvedUsd() == null
                                ? DASH + " (nothing resolved, or no cost to divide)"
                                : (r.costComplete() ? "" : "at least ")
                                        + "USD "
                                        + r.costPerResolvedUsd().setScale(4, RoundingMode.HALF_UP),
                        null));
        cost.add(
                new Row(
                        "Platform charges",
                        r.chargesMixedCurrency()
                                ? DASH + " (billable charges are in more than one currency and cannot be summed)"
                                : r.platformChargesMinor() == 0
                                        ? "none recorded"
                                        : r.platformChargesMinor() / 100
                                                + "."
                                                + String.format("%02d", r.platformChargesMinor() % 100)
                                                + " "
                                                + (r.platformChargesCurrency() == null
                                                        ? ""
                                                        : r.platformChargesCurrency()),
                        "billable, undisputed resolution events"));
        sections.add(new Section("Cost (estimated)", cost, null));

        // 5. ROI
        sections.add(roi(r));

        List<String> notes =
                List.of(
                        "Period: "
                                + r.from()
                                + " to "
                                + r.to()
                                + " (UTC days, inclusive). Generated "
                                + r.generatedAt()
                                + ".",
                        "This report contains only data recorded by the platform and the"
                                + " tenant-entered numbers in the assumptions box. It makes no claim"
                                + " and uses no benchmark from outside that data.");
        return new PilotReportDoc(
                "Pilot report: " + r.agentName(),
                "Reporting period " + r.from() + " to " + r.to(),
                r.demo(),
                r.demoLabel(),
                sections,
                r.assumptions(),
                notes);
    }

    private static Section roi(PilotReport r) {
        String heading = "ROI (" + r.roiBasis() + ")";
        String note = r.roiNote() == null ? "NO_ASSUMPTIONS" : r.roiNote();
        String msg =
                switch (note) {
                    case "NO_ASSUMPTIONS" ->
                            "Not computed: no value per resolved conversation has been entered for"
                                    + " this agent. Enter one under Cost & ROI to see an ROI figure.";
                    case "NO_VALUE_ASSUMPTION" ->
                            "Not computed: only minutes saved were entered, no money value.";
                    case "CHARGES_MIXED_CURRENCY" ->
                            "Not computed: billable platform charges are in more than one currency and"
                                    + " the platform holds no exchange rate, so a net value would ignore them.";
                    case "CURRENCY_MISMATCH" ->
                            "Not computed: the value is not in USD (or platform charges are in"
                                    + " another currency) and the platform holds no exchange rate.";
                    default -> null;
                };
        if (msg != null) {
            List<Row> rows = new ArrayList<>();
            if (r.valueTotal() != null) {
                rows.add(new Row("Assumed value of resolved conversations", cur(r) + num(r.valueTotal()), "assumption x resolved"));
            }
            if (r.minutesSavedTotal() != null) {
                rows.add(new Row("Assumed minutes saved", num(r.minutesSavedTotal()), "assumption x resolved"));
            }
            return new Section(heading, rows, msg);
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Assumed value of resolved conversations", "USD " + num(r.valueTotal()), "assumption x resolved"));
        if (r.minutesSavedTotal() != null) {
            rows.add(new Row("Assumed minutes saved", num(r.minutesSavedTotal()), "assumption x resolved"));
        }
        rows.add(new Row("Net of recorded cost", "USD " + num(r.netValue()), "cost here = your own provider cost + platform charges; platform-hosted model usage is billed through platform charges, so it can differ from the estimated model cost above"));
        rows.add(
                new Row(
                        "ROI",
                        r.roiPercent() == null ? DASH : r.roiPercent().toPlainString() + "%",
                        r.roiPercent() == null
                                ? "no cost recorded, so no percentage"
                                : "net / cost; cost is a lower bound when the cost section says so"));
        return new Section(heading, rows, "COST_INCOMPLETE".equals(note)
                ? "The cost is incomplete (see Cost), so this ROI is an upper bound."
                : null);
    }

    private static String num(BigDecimal n) {
        return n == null ? DASH : n.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal ratio) {
        return ratio == null
                ? DASH
                : ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String cur(PilotReport r) {
        return r.valueCurrency() == null ? "" : r.valueCurrency() + " ";
    }

    private static String seconds(BigDecimal sec) {
        return sec.compareTo(BigDecimal.ONE) < 0
                ? sec.movePointRight(3).setScale(0, RoundingMode.HALF_UP).toPlainString() + " ms"
                : sec.setScale(2, RoundingMode.HALF_UP).toPlainString() + " s";
    }

    /** A cost of nothing recorded is a dash with the reason, never "0". */
    private static String costText(PilotReport r) {
        if (r.estimatedCostUsd() == null || r.modelCalls() == 0) {
            return DASH + " (no model calls recorded)";
        }
        BigDecimal c = r.estimatedCostUsd().setScale(4, RoundingMode.HALF_UP);
        if (!r.costComplete() && c.signum() == 0) {
            return DASH + " (no price entered for the models used)";
        }
        return (r.costComplete() ? "" : "at least ") + "USD " + c.toPlainString();
    }
}
