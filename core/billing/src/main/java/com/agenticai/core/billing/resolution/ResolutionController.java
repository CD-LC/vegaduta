package com.agenticai.core.billing.resolution;

import com.agenticai.core.billing.resolution.ResolutionDtos.DisputeRequest;
import com.agenticai.core.billing.resolution.ResolutionDtos.RecordResolutionRequest;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionActionResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionEventResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionListResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionSummaryResponse;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-facing surface of the resolution meter (Tier-4 outcome-based
 * pricing substrate; table + vocabulary in tenant V280):
 * <ul>
 *   <li>{@code GET /api/tenant/resolutions/summary?from&to} - outcome
 *       counts plus the billable total over a period (defaults: trailing
 *       30 days)</li>
 *   <li>{@code GET /api/tenant/resolutions?from&to&limit&offset} - the
 *       underlying events, newest first</li>
 *   <li>{@code POST /api/tenant/resolutions} - explicitly record a
 *       resolution for a conversation the sweep cannot see (non-channel
 *       sessions, external helpdesk imports)</li>
 *   <li>{@code POST /api/tenant/resolutions/{id}/dispute} - contest one
 *       billable event; the row stays, billable flips off, who/why is kept</li>
 * </ul>
 *
 * <p>All four routes are {@code PERM_MANAGE_BILLING}-gated in
 * {@code SecurityConfig}, same trust tier as invoices - this surface is
 * what a tenant audits their outcome-based bill against. Tenant scoping is
 * the schema-per-tenant connection itself (the table is tenant-schema);
 * nothing here takes a tenant id from the caller.
 */
@RestController
@Profile("security")
public class ResolutionController {

    private static final int MAX_LIMIT = 500;

    private final ResolutionEventRepository repository;
    private final ResolutionMeteringService metering;
    private final ResolutionPricingService pricing;

    public ResolutionController(
        ResolutionEventRepository repository,
        ResolutionMeteringService metering,
        ResolutionPricingService pricing
    ) {
        this.repository = repository;
        this.metering = metering;
        this.pricing = pricing;
    }

    // Both reads are @Transactional(readOnly) for two reasons. The repository is a plain JdbcTemplate
    // over a tenant table with unqualified names: outside a Spring transaction it borrows a pooled
    // connection that is still on the default schema, so the query silently misses the tenant's
    // rows. And summary() runs two queries that must see one snapshot.
    @GetMapping("/api/tenant/resolutions/summary")
    @Transactional(readOnly = true)
    public ResolutionSummaryResponse summary(
        @RequestParam(value = "from", required = false) Instant from,
        @RequestParam(value = "to", required = false) Instant to
    ) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        List<ResolutionEventRepository.OutcomeCount> counts = repository.countByOutcome(start, end);
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        long total = 0;
        for (ResolutionEventRepository.OutcomeCount c : counts) {
            byOutcome.put(c.outcome().name(), c.count());
            total += c.count();
        }
        ResolutionEventRepository.BillableTotals billable = repository.billableTotals(start, end);
        ResolutionPricingService.PricingSnapshot price = pricing.snapshot();
        return new ResolutionSummaryResponse(
            start, end, total, byOutcome,
            billable.billableCount(), billable.totalMinor(),
            billable.currency() != null ? billable.currency() : price.currency(),
            price.unitPriceMinor(),
            price.unitPriceMinor() == 0);
    }

    @GetMapping("/api/tenant/resolutions")
    @Transactional(readOnly = true)
    public ResolutionListResponse list(
        @RequestParam(value = "from", required = false) Instant from,
        @RequestParam(value = "to", required = false) Instant to,
        @RequestParam(value = "limit", defaultValue = "100") int limit,
        @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<ResolutionEventResponse> events = repository.listPage(start, end, capped, Math.max(offset, 0))
            .stream().map(ResolutionEventResponse::from).toList();
        return new ResolutionListResponse(events, capped, Math.max(offset, 0));
    }

    @PostMapping("/api/tenant/resolutions")
    public ResolutionActionResponse record(@RequestBody RecordResolutionRequest body) {
        if (body.conversationKey() == null || body.conversationKey().isBlank()
            || body.agentId() == null
            || body.dedupeKey() == null || body.dedupeKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "conversationKey, agentId and dedupeKey are required");
        }
        ResolutionOutcome outcome = parseEnum(ResolutionOutcome.class, body.outcome(), "outcome");
        ResolutionEvidence evidence = body.evidence() == null || body.evidence().isBlank()
            ? ResolutionEvidence.EXPLICIT_API
            : parseEnum(ResolutionEvidence.class, body.evidence(), "evidence");
        return metering.record(new ResolutionMeteringService.RecordRequest(
            body.conversationKey().trim(),
            body.agentId(),
            body.channel(),
            outcome,
            evidence,
            body.confidence(),
            body.resolvedAt(),
            body.dedupeKey().trim(),
            currentSubject()))
            .map(id -> ResolutionActionResponse.of(id, true))
            .orElseGet(() -> ResolutionActionResponse.of(null, false));
    }

    @PostMapping("/api/tenant/resolutions/{id}/dispute")
    public ResolutionActionResponse dispute(@PathVariable("id") UUID id, @RequestBody DisputeRequest body) {
        String reason = body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "a non-empty reason (<= 500 chars) is required to dispute a resolution");
        }
        boolean disputed = metering.dispute(id, currentSubject(), reason);
        if (!disputed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such resolution event for this tenant");
        }
        return ResolutionActionResponse.of(id, true);
    }

    private String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "invalid " + field + " '" + raw + "' - expected one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}
