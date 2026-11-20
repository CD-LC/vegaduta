package com.agenticai.core.billing.resolution;

import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionEventResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionListResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionSummaryResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the resolution meter for {@link ResolutionController}: wraps {@link
 * ResolutionEventRepository} and {@link ResolutionPricingService} and owns the read-only
 * transaction.
 *
 * <p>Both reads are {@code @Transactional(readOnly)} for two reasons. The repository is a plain
 * JdbcTemplate over a tenant table with unqualified names: outside a Spring transaction it borrows
 * a pooled connection that is still on the default schema, so the query silently misses the
 * tenant's rows. And {@link #summary} runs two queries that must see one snapshot.
 */
@Service
public class ResolutionQueryService {

    private static final int MAX_LIMIT = 500;

    private final ResolutionEventRepository repository;
    private final ResolutionPricingService pricing;

    public ResolutionQueryService(
            ResolutionEventRepository repository, ResolutionPricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    @Transactional(readOnly = true)
    public ResolutionSummaryResponse summary(Instant from, Instant to) {
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
                start,
                end,
                total,
                byOutcome,
                billable.billableCount(),
                billable.totalMinor(),
                billable.currency() != null ? billable.currency() : price.currency(),
                price.unitPriceMinor(),
                price.unitPriceMinor() == 0);
    }

    @Transactional(readOnly = true)
    public ResolutionListResponse list(Instant from, Instant to, int limit, int offset) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<ResolutionEventResponse> events =
                repository.listPage(start, end, capped, Math.max(offset, 0)).stream()
                        .map(ResolutionEventResponse::from)
                        .toList();
        return new ResolutionListResponse(events, capped, Math.max(offset, 0));
    }
}
