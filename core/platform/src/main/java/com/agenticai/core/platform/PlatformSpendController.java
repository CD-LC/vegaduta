package com.agenticai.core.platform;

import com.agenticai.core.platform.PlatformSpendService.Range;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-wide spend reports - {@code /api/platform/spend/**}, gated by the blanket {@code
 * SecurityConfig} rule {@code /api/platform/** -> ROLE_platform-admin}.
 *
 * <p>Every report carries both an all-provider usage figure and a hosted-pool (PLATFORM_HOSTED)
 * figure; see {@link PlatformSpendService} for the exact meaning and the caps ({@code from}/{@code
 * to} are UTC dates, default trailing 30 days, max 92). Invalid ranges surface as {@link
 * IllegalArgumentException} (handled as 400, as in {@link PlatformUserController}). Every endpoint
 * takes an optional {@code tenantId} (tenant UUID) restricting the report to that one tenant; an
 * unknown id is a 404.
 */
@RestController
@RequestMapping("/api/platform/spend")
@Profile("security")
public class PlatformSpendController {

    private final PlatformSpendService service;

    public PlatformSpendController(PlatformSpendService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public PlatformSpendService.SummaryReport summary(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(name = "tenantId", required = false) UUID tenantId) {
        return service.summary(PlatformSpendService.resolveRange(from, to), tenantId);
    }

    @GetMapping("/by-day")
    public PlatformSpendService.DayReport byDay(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(name = "tenantId", required = false) UUID tenantId) {
        return service.byDay(PlatformSpendService.resolveRange(from, to), tenantId);
    }

    @GetMapping("/by-tenant")
    public PlatformSpendService.TenantReport byTenant(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(name = "tenantId", required = false) UUID tenantId) {
        return service.byTenant(PlatformSpendService.resolveRange(from, to), tenantId);
    }

    @GetMapping("/by-model")
    public PlatformSpendService.ModelReport byModel(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(name = "tenantId", required = false) UUID tenantId) {
        return service.byModel(PlatformSpendService.resolveRange(from, to), tenantId);
    }

    @GetMapping("/by-provider")
    public PlatformSpendService.ProviderReport byProvider(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(name = "tenantId", required = false) UUID tenantId) {
        return service.byProvider(PlatformSpendService.resolveRange(from, to), tenantId);
    }

    /** {@code groupBy}: tenant (default) | day | model | provider. Bounded, so built in memory. */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(name = "groupBy", defaultValue = "tenant") String groupBy,
            @RequestParam(name = "tenantId", required = false) UUID tenantId) {
        Range range = PlatformSpendService.resolveRange(from, to);
        String group = groupBy == null ? "" : groupBy.trim().toLowerCase(Locale.ROOT);
        if (!PlatformSpendService.GROUPS.contains(group)) {
            throw new IllegalArgumentException(
                    "groupBy must be one of: tenant, day, model, provider");
        }
        byte[] body = service.exportCsv(group, range, tenantId).getBytes(StandardCharsets.UTF_8);
        String filename =
                "platform-spend-" + group + "-" + range.from() + "_to_" + range.to() + ".csv";
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }
}
