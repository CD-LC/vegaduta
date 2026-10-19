package com.agenticai.core.chat;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Phase P4 (Spend &amp; Reports) read surface -
 * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §3 section 4. Deliberately a NEW
 * controller (not piled onto {@code PlatformController}): it is the direct
 * sibling of {@code LlmCallLogController} and shares its home at {@code
 * /api/admin/**} (tenant-admin gated by {@code SecurityConfig}), reporting on
 * the CURRENT tenant's own LLM spend/volume from the {@code
 * llm_daily_usage_rollup} table - never a raw scan of {@code llm_call_log} at
 * volume (§8), with a single bounded live query covering only today (see
 * {@link LlmUsageRollupService}).
 *
 * <p>Bare DTOs returned directly, constructor injection, no {@code
 * ResponseEntity} wrapping except the streamed CSV - same style as its
 * sibling. Every endpoint takes an optional {@code from}/{@code to} ISO date
 * range (default: the last 30 days, inclusive).
 */
@RestController
@RequestMapping("/api/admin/spend-reports")
public class SpendReportController {

    private static final int DEFAULT_RANGE_DAYS = 30;

    private final LlmUsageRollupService rollupService;

    public SpendReportController(LlmUsageRollupService rollupService) {
        this.rollupService = rollupService;
    }

    /** Overall totals: spend, token &amp; message volume, success rate, latency (mean/p50/p95). */
    @GetMapping("/summary")
    public SpendReport.Summary summary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Range r = resolveRange(from, to);
        return rollupService.summary(r.from(), r.to());
    }

    @GetMapping("/by-day")
    public List<SpendReport.DayUsage> byDay(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Range r = resolveRange(from, to);
        return rollupService.byDay(r.from(), r.to());
    }

    @GetMapping("/by-model")
    public List<SpendReport.ModelUsage> byModel(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Range r = resolveRange(from, to);
        return rollupService.byModel(r.from(), r.to());
    }

    @GetMapping("/by-provider")
    public List<SpendReport.ProviderUsage> byProvider(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Range r = resolveRange(from, to);
        return rollupService.byProvider(r.from(), r.to());
    }

    @GetMapping("/by-agent")
    public List<SpendReport.AgentUsage> byAgent(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Range r = resolveRange(from, to);
        return rollupService.byAgent(r.from(), r.to());
    }

    @GetMapping("/by-modality")
    public List<SpendReport.ModalityUsage> byModality(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        Range r = resolveRange(from, to);
        return rollupService.byModality(r.from(), r.to());
    }

    /**
     * Streamed CSV export of any breakdown ({@code groupBy}: day / model /
     * provider / agent / modality, default day). Streamed via {@link
     * StreamingResponseBody} so the rows are written straight to the socket as
     * they are produced rather than buffered into one big response body.
     */
    @GetMapping("/export.csv")
    public ResponseEntity<StreamingResponseBody> exportCsv(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "day") String groupBy
    ) {
        Range r = resolveRange(from, to);
        SpendReport.GroupBy dimension = parseGroupBy(groupBy);

        StreamingResponseBody body = outputStream -> {
            try (Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                rollupService.writeCsv(writer, r.from(), r.to(), dimension);
            }
        };

        String filename = "spend-report-" + dimension.name().toLowerCase(java.util.Locale.ROOT)
            + "-" + r.from() + "_to_" + r.to() + ".csv";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(body);
    }

    private static SpendReport.GroupBy parseGroupBy(String raw) {
        try {
            return SpendReport.GroupBy.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "groupBy must be one of: day, model, provider, agent, modality"
            );
        }
    }

    /**
     * Resolves the effective inclusive date range: defaults to the last
     * {@value #DEFAULT_RANGE_DAYS} days ending today (UTC) when either bound
     * is omitted, and rejects an inverted range up front.
     */
    private Range resolveRange(LocalDate from, LocalDate to) {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate resolvedTo = to != null ? to : today;
            LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(DEFAULT_RANGE_DAYS - 1L);
            if (resolvedFrom.isAfter(resolvedTo)) {
                throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "'from' must not be after 'to'"
                );
            }
            return new Range(resolvedFrom, resolvedTo);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "from/to must be ISO dates (yyyy-MM-dd)"
            );
        }
    }

    private record Range(LocalDate from, LocalDate to) {
    }
}
