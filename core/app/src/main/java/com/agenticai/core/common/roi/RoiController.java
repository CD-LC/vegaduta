package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.RoiDtos.AgentRoiResponse;
import com.agenticai.core.common.roi.RoiDtos.AgentRoiRow;
import com.agenticai.core.common.roi.RoiDtos.AssumptionRequest;
import com.agenticai.core.common.roi.RoiDtos.AssumptionResponse;
import com.agenticai.core.common.roi.RoiDtos.BudgetResponse;
import com.agenticai.core.common.roi.RoiDtos.CostReportResponse;
import com.agenticai.core.common.roi.RoiDtos.OutcomeRequest;
import com.agenticai.core.common.roi.RoiDtos.OutcomeResponse;
import com.agenticai.core.common.roi.RoiDtos.PriceListResponse;
import com.agenticai.core.common.roi.RoiDtos.PriceRequest;
import com.agenticai.core.common.roi.RoiDtos.PriceResponse;
import com.agenticai.core.common.roi.PilotDtos.BaselineRequest;
import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import com.agenticai.core.common.roi.PilotDtos.DemoStatus;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-agent cost and ROI view. Reads need {@code PERM_VIEW_USAGE}; writes (prices, value
 * assumptions, outcome marks) need {@code PERM_MANAGE_BILLING} - see {@code TenantFeatureRules}.
 * Tenant scoping is the schema-per-tenant connection itself: nothing here takes a tenant id from
 * the caller. Dates are UTC calendar days, {@code from}/{@code to} inclusive, default last 30 days.
 */
@RestController
@RequestMapping("/api/tenant/roi")
public class RoiController {

    private final RoiPriceService prices;
    private final RoiCostService costs;
    private final RoiAgentService agents;
    private final RoiOutcomeService outcomes;
    private final RoiBudgetService budget;
    private final RoiBaselineService baselines;
    private final PilotReportService pilotReports;

    public RoiController(
            RoiPriceService prices,
            RoiCostService costs,
            RoiAgentService agents,
            RoiOutcomeService outcomes,
            RoiBudgetService budget,
            RoiBaselineService baselines,
            PilotReportService pilotReports) {
        this.prices = prices;
        this.costs = costs;
        this.agents = agents;
        this.outcomes = outcomes;
        this.budget = budget;
        this.baselines = baselines;
        this.pilotReports = pilotReports;
    }

    @GetMapping("/prices")
    public PriceListResponse prices(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        LocalDate[] r = RoiCostService.range(from, to);
        return new PriceListResponse(prices.list(), costs.unpriced(r[0], r[1]), "USD");
    }

    @PostMapping("/prices")
    public PriceResponse setPrice(@RequestBody PriceRequest body) {
        return prices.upsert(body, subject());
    }

    @DeleteMapping("/prices/{id}")
    public ResponseEntity<Void> deletePrice(@PathVariable UUID id) {
        if (!prices.delete(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No such price for this tenant");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/costs")
    public CostReportResponse costs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "agent") String groupBy) {
        LocalDate[] r = RoiCostService.range(from, to);
        return costs.report(r[0], r[1], groupBy, costs.platformCharges(r[0], r[1]));
    }

    @GetMapping("/agents")
    public AgentRoiResponse agents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeAssumed) {
        LocalDate[] r = RoiCostService.range(from, to);
        return agents.table(r[0], r[1], includeAssumed);
    }

    @GetMapping(value = "/agents.csv", produces = "text/csv")
    public ResponseEntity<byte[]> agentsCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeAssumed) {
        LocalDate[] r = RoiCostService.range(from, to);
        AgentRoiResponse table = agents.table(r[0], r[1], includeAssumed);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agent-roi-" + r[0] + "_to_" + r[1] + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv(table).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/agents/{agentId}/assumptions")
    public AssumptionResponse assumption(@PathVariable UUID agentId) {
        return agents.assumption(agentId);
    }

    @PutMapping("/agents/{agentId}/assumptions")
    public AssumptionResponse setAssumption(
            @PathVariable UUID agentId, @RequestBody AssumptionRequest body) {
        return agents.setAssumption(agentId, body, subject());
    }

    @PostMapping("/outcomes")
    public OutcomeResponse markOutcome(@RequestBody OutcomeRequest body) {
        return outcomes.mark(body, subject());
    }

    // ---- pilot kit: tenant-entered baseline, pilot report, demo marker ------------------------

    @GetMapping("/agents/{agentId}/baseline")
    public BaselineResponse baseline(@PathVariable UUID agentId) {
        return baselines.get(agentId);
    }

    @PutMapping("/agents/{agentId}/baseline")
    public BaselineResponse setBaseline(
            @PathVariable UUID agentId, @RequestBody BaselineRequest body) {
        return baselines.put(agentId, body, subject());
    }

    @GetMapping("/demo")
    public DemoStatus demo() {
        return pilotReports.demo();
    }

    /**
     * One agent's pilot report over a date range: {@code format=html} (default), {@code pdf} or
     * {@code json}. Contains only recorded data and the tenant-entered numbers listed in its
     * assumptions box.
     */
    @GetMapping("/agents/{agentId}/pilot-report")
    public ResponseEntity<?> pilotReport(
            @PathVariable UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeAssumed,
            @RequestParam(defaultValue = "html") String format)
            throws IOException {
        String fmt = format.toLowerCase();
        if (!List.of("html", "pdf", "json").contains(fmt)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "format must be html, pdf or json");
        }
        LocalDate[] r = RoiCostService.range(from, to);
        PilotDtos.PilotReport report =
                pilotReports.build(
                        agentId, r[0], r[1], includeAssumed, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        if (fmt.equals("json")) {
            return ResponseEntity.ok(report);
        }
        PilotReportDoc doc = PilotReportDoc.from(report);
        String base = "pilot-report-" + r[0] + "_to_" + r[1];
        if (fmt.equals("pdf")) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + base + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(PilotReportPdf.render(doc));
        }
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + base + ".html\"")
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .body(PilotReportHtml.render(doc).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/budget")
    public BudgetResponse budget() {
        return budget.status();
    }

    private static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    static final List<String> CSV_HEADER =
            List.of(
                    "agent_id",
                    "agent",
                    "conversations",
                    "resolved",
                    "resolved_confirmed",
                    "resolved_assumed_idle_close",
                    "handed_to_human",
                    "abandoned",
                    "unresolved",
                    "human_corrected",
                    "handover_rate",
                    "human_correction_rate",
                    "approvals_decided",
                    "approvals_denied",
                    "approval_denial_rate",
                    "byok_estimated_cost_usd",
                    "platform_hosted_estimated_cost_usd",
                    "estimated_cost_usd",
                    "estimated_cost_per_resolved_usd",
                    "cost_complete",
                    "platform_charges_minor",
                    "platform_charges_currency",
                    "value_per_resolution_assumed",
                    "value_currency",
                    "minutes_saved_per_resolution_assumed",
                    "value_total_based_on_your_assumptions",
                    "minutes_saved_total_based_on_your_assumptions",
                    "net_value_based_on_your_assumptions",
                    "roi_percent_based_on_your_assumptions",
                    "roi_note");

    static String csv(AgentRoiResponse t) {
        StringBuilder sb = new StringBuilder(String.join(",", CSV_HEADER)).append("\r\n");
        for (AgentRoiRow a : t.agents()) {
            List<Object> cells =
                    List.of(
                            a.agentId(),
                            a.agentName(),
                            a.conversations(),
                            a.resolved(),
                            a.resolvedConfirmed(),
                            a.resolvedAssumed(),
                            a.handedToHuman(),
                            a.abandoned(),
                            a.unresolved(),
                            a.humanCorrected(),
                            n(a.handoverRate()),
                            n(a.humanCorrectionRate()),
                            a.approvalsDecided(),
                            a.approvalsDenied(),
                            n(a.approvalDenialRate()),
                            n(a.byokEstimatedCostUsd()),
                            n(a.platformHostedEstimatedCostUsd()),
                            n(a.estimatedCostUsd()),
                            n(a.estimatedCostPerResolvedUsd()),
                            a.costComplete(),
                            a.platformChargesMinor(),
                            n(a.platformChargesCurrency()),
                            n(a.valuePerResolution()),
                            n(a.valueCurrency()),
                            n(a.minutesSavedPerResolution()),
                            n(a.valueTotal()),
                            n(a.minutesSavedTotal()),
                            n(a.netValue()),
                            n(a.roiPercent()),
                            n(a.roiNote()));
            sb.append(String.join(",", cells.stream().map(RoiController::cell).toList()))
                    .append("\r\n");
        }
        return sb.toString();
    }

    private static Object n(Object o) {
        return o == null ? "" : o;
    }

    /**
     * RFC 4180 quoting plus a leading apostrophe on formula-looking text so spreadsheets do not
     * execute it.
     */
    static String cell(Object o) {
        String s = o instanceof BigDecimal b ? b.toPlainString() : String.valueOf(o);
        if (!(o instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
