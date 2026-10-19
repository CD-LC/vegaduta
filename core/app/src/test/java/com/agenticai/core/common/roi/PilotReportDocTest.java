package com.agenticai.core.common.roi;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.roi.PilotDtos.AssumptionLine;
import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import com.agenticai.core.common.roi.PilotDtos.PilotReport;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The pilot report's wording rules: a missing number is a dash with a reason (never zero), costs
 * without a price are a lower bound, ROI needs the tenant's own value, and only recorded or
 * tenant-entered numbers appear. Both renderers draw the same document.
 */
class PilotReportDocTest {

    private static final LocalDate FROM = LocalDate.parse("2026-09-01");
    private static final LocalDate TO = LocalDate.parse("2026-09-30");

    private static BaselineResponse noBaseline() {
        return new BaselineResponse(
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null,
                PilotDtos.TENANT_ENTERED);
    }

    /** Zero volume, nothing entered, nothing priced. */
    private static PilotReport empty(boolean demo) {
        return new PilotReport(
                demo, demo ? "Example Clinic" : null, UUID.randomUUID(), "Reminder bot", FROM, TO,
                Instant.parse("2026-10-01T00:00:00Z"), false, 0, 0, 0, 0, null, null, null, null, 0,
                null, true, null, 0, null, null, null, null, null, "NO_ASSUMPTIONS",
                RoiMetrics.ROI_BASIS, noBaseline(), List.of(), 0, null, false);
    }

    private static String text(PilotReportDoc d) {
        StringBuilder s = new StringBuilder(d.title()).append('\n');
        d.sections().forEach(sec -> {
            s.append(sec.heading()).append('\n');
            sec.rows().forEach(r -> s.append(r.label()).append(": ").append(r.value()).append('\n'));
            if (sec.message() != null) {
                s.append(sec.message()).append('\n');
            }
        });
        return s.toString();
    }

    @Test
    void zeroVolumeShowsNoRatesAndSaysWhy() {
        PilotReportDoc d = PilotReportDoc.from(empty(false));
        String t = text(d);
        assertThat(t).contains("No conversations with a recorded outcome");
        assertThat(t).doesNotContain("0.0%").doesNotContain("100.0%");
        assertThat(t).contains("no successful calls recorded");
        assertThat(t).contains("Not computed: no value per resolved conversation");
        assertThat(t).contains("Estimated model cost: " + PilotReportDoc.DASH + " (no model calls recorded)");
        assertThat(t).doesNotContain("USD 0.0000");
    }

    @Test
    void missingBaselineIsADashNotZero() {
        String t = text(PilotReportDoc.from(empty(false)));
        assertThat(t).contains("Baseline average handling time (tenant-entered): " + PilotReportDoc.DASH + " (not entered)");
        assertThat(t).contains("baseline " + PilotReportDoc.DASH + " (not entered)");
        assertThat(t).doesNotContain("baseline 0 per month");
    }

    @Test
    void missingPricesMakeCostALowerBoundAndIncompleteRoiAnUpperBound() {
        PilotReport r = empty(false);
        BaselineResponse b =
                new BaselineResponse(r.agentId(), "LEAD_FOLLOW_UP", "Aug 2026", new BigDecimal("400"),
                        new BigDecimal("6.5"), new BigDecimal("0.25"), new BigDecimal("1500"), "INR", "u",
                        Instant.now(), Instant.now(), PilotDtos.TENANT_ENTERED);
        PilotReport withData =
                new PilotReport(false, null, r.agentId(), "Lead bot", FROM, TO, r.generatedAt(), false, 100,
                        60, 20, 5, new BigDecimal("0.6000"), new BigDecimal("0.2000"), new BigDecimal("0.0500"),
                        new BigDecimal("2.40"), 300, new BigDecimal("1.5"), false, new BigDecimal("0.025"), 0,
                        null, new BigDecimal("9000"), null, new BigDecimal("8998.5"), new BigDecimal("599900.0"),
                        "COST_INCOMPLETE", RoiMetrics.ROI_BASIS, b, List.of(), 300, "USD", false);
        String t = text(PilotReportDoc.from(withData));
        assertThat(t).contains("Estimated model cost: at least USD 1.5000");
        assertThat(t).contains("Resolved by the agent: 60 (60.0%)");
        assertThat(t).contains("Handed to a human: 20 (20.0%)");
        assertThat(t).contains("Corrected by a human: 5 (5.0%)");
        assertThat(t).contains("2.40 s (300 calls)");
        assertThat(t).contains("Estimated cost per resolved conversation: at least USD 0.0250");
        assertThat(t).contains("baseline 400 per month (tenant-entered); during the pilot 100 conversations in 30 days");
        assertThat(t).contains("baseline 25.0% (tenant-entered)");
        assertThat(t).contains("The cost is incomplete");
        assertThat(t).contains("ROI (based on your assumptions)");
    }

    @Test
    void currencyMismatchComputesNoNet() {
        PilotReport r = empty(false);
        PilotReport mismatch =
                new PilotReport(false, null, r.agentId(), "Bot", FROM, TO, r.generatedAt(), false, 10, 5, 0, 0,
                        new BigDecimal("0.5"), new BigDecimal("0"), new BigDecimal("0"), null, 0, null, true, null,
                        0, null, new BigDecimal("2500"), null, null, null, "CURRENCY_MISMATCH",
                        RoiMetrics.ROI_BASIS, noBaseline(), List.of(), 10, "INR", false);
        String t = text(PilotReportDoc.from(mismatch));
        assertThat(t).contains("Not computed: the value is not in USD");
        assertThat(t).contains("Assumed value of resolved conversations: INR 2500.00");
        assertThat(t).doesNotContain("Net of recorded cost");
    }

    @Test
    void htmlEscapesEverythingAndListsAssumptions() {
        PilotReport r = empty(false);
        PilotReport evil =
                new PilotReport(false, null, r.agentId(), "<script>alert(1)</script> & \"co\"", FROM, TO,
                        r.generatedAt(), false, 0, 0, 0, 0, null, null, null, null, 0, null, true, null, 0, null,
                        null, null, null, null, "NO_ASSUMPTIONS", RoiMetrics.ROI_BASIS, noBaseline(),
                        List.of(new AssumptionLine("Pilot job", "<b>LEAD</b>", PilotDtos.TENANT_ENTERED)), 0, null, false);
        String html = PilotReportHtml.render(PilotReportDoc.from(evil));
        assertThat(html).doesNotContain("<script>").doesNotContain("<b>LEAD");
        assertThat(html).contains("&lt;script&gt;").contains("&lt;b&gt;LEAD&lt;/b&gt;");
        assertThat(html).contains("Assumptions").contains("tenant-entered");
        assertThat(html).doesNotContain("class=\"wm\"");
    }

    @Test
    void demoReportsAreWatermarkedInBothFormats() throws IOException {
        PilotReportDoc d = PilotReportDoc.from(empty(true));
        String html = PilotReportHtml.render(d);
        assertThat(html).contains("class=\"wm\"").contains("EXAMPLE").contains("NOT A CUSTOMER RESULT");
        byte[] pdf = PilotReportPdf.render(d);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String txt = new PDFTextStripper().getText(doc);
            assertThat(txt).contains("EXAMPLE").contains("NOT A CUSTOMER RESULT");
        }
        try (PDDocument doc = Loader.loadPDF(PilotReportPdf.render(PilotReportDoc.from(empty(false))))) {
            assertThat(new PDFTextStripper().getText(doc)).doesNotContain("EXAMPLE");
        }
    }

    @Test
    void pdfIsValidAndSurvivesCharactersHelveticaCannotDraw() throws IOException {
        PilotReport r = empty(false);
        PilotReport odd =
                new PilotReport(false, null, r.agentId(), "VegaDūta हिन्दी bot", FROM,
                        TO, r.generatedAt(), false, 0, 0, 0, 0, null, null, null, null, 0, null, true, null, 0,
                        null, null, null, null, null, "NO_ASSUMPTIONS", RoiMetrics.ROI_BASIS, noBaseline(),
                        List.of(new AssumptionLine("Baseline period", "₹ Q3 – 2026", PilotDtos.TENANT_ENTERED)), 0, null, false);
        byte[] pdf = PilotReportPdf.render(PilotReportDoc.from(odd));
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            String txt = new PDFTextStripper().getText(doc);
            assertThat(txt).contains("Assumptions").contains("tenant-entered").contains("Vega");
        }
    }

    @Test
    void unpricedModelsGiveADashNotAZeroCost() {
        PilotReport r = empty(false);
        PilotReport unpriced =
                new PilotReport(false, null, r.agentId(), "Bot", FROM, TO, r.generatedAt(), false, 5, 3, 1, 0,
                        new BigDecimal("0.6"), new BigDecimal("0.2"), new BigDecimal("0"), new BigDecimal("0.004"), 9,
                        BigDecimal.ZERO, false, null, 0, null, null, null, null, null, "NO_ASSUMPTIONS",
                        RoiMetrics.ROI_BASIS, noBaseline(), List.of(), 9, null, false);
        String t = text(PilotReportDoc.from(unpriced));
        assertThat(t).contains("Estimated model cost: " + PilotReportDoc.DASH + " (no price entered for the models used)");
        assertThat(t).doesNotContain("USD 0.0000");
        assertThat(t).contains("4 ms (9 calls)"); // a sub-second median is not rounded to 0.00 s
    }

    @Test
    void chargesInSeveralCurrenciesAreNotReportedAsNoneAndRoiIsWithheld() {
        PilotReport r = empty(false);
        PilotReport mixed =
                new PilotReport(false, null, r.agentId(), "Bot", FROM, TO, r.generatedAt(), false, 10, 5, 0, 0,
                        new BigDecimal("0.5"), new BigDecimal("0"), new BigDecimal("0"), null, 0, new BigDecimal("1"),
                        true, new BigDecimal("0.2"), 0, null, new BigDecimal("60"), null, null, null,
                        "CHARGES_MIXED_CURRENCY", RoiMetrics.ROI_BASIS, noBaseline(), List.of(), 4, "USD", true);
        String t = text(PilotReportDoc.from(mixed));
        assertThat(t).doesNotContain("none recorded");
        assertThat(t).contains("more than one currency and cannot be summed");
        assertThat(t).contains("Not computed: billable platform charges are in more than one currency");
        assertThat(t).doesNotContain("Net of recorded cost");
    }
}
