package com.agenticai.core.platform.moderation;

import com.agenticai.core.guardrails.moderation.ModerationReviewItem;
import com.agenticai.core.guardrails.moderation.ModerationReviewNotFoundException;
import com.agenticai.core.guardrails.moderation.ModerationReviewService;
import com.agenticai.core.guardrails.moderation.ModerationReviewStatus;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.tenancy.TenantStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * The platform admin's cross-tenant read/act layer over the LIVE moderation review queue - the
 * per-tenant-schema {@code moderation_review_item} table (tenant V121) written by the guardrails
 * engine whenever a signal lands in a pack's review band.
 *
 * <p><b>Why a fan-out and not a query.</b> The queue is deliberately per-tenant-schema: V121's
 * isolation argument is that flagged content - PII, harassment, self-harm - inherits the platform's
 * strongest existing isolation boundary (schema-per-tenant) for free. There is no cross-schema
 * view, and building one would poke a hole in exactly that boundary. The platform admin sits above
 * any single tenant, so the honest shape of a cross-tenant view is to iterate the tenants - the
 * same pattern {@code PlatformTenantMetricsService} already uses for cross-tenant observability.
 * Per-tenant results are capped and this is admin-panel volume, not a hot path (the same honest
 * no-caching note {@code PlatformSettingsService} makes about itself).
 *
 * <p><b>Every decision still goes through the one audited path.</b> All acts delegate to
 * guardrails' {@link ModerationReviewService}, which writes every transition to {@code
 * AuditLogService} - "there is no un-audited path to resolve an item" survives the fan-out by
 * construction.
 *
 * <p>(The predecessor of this class read a control-plane copy of the queue whose {@code enqueue}
 * had no caller - a table that was unreachable-by- construction and therefore always empty. V216
 * dropped it.)
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class PlatformModerationQueueService {

    /**
     * Cap per TENANT, not global: a page assembled across N tenants can carry up to N x this.
     * Deliberate - truncating one noisy tenant must not hide another tenant's items, and at
     * admin-panel volume the multiplied bound stays small.
     */
    static final int MAX_ITEMS_PER_TENANT = 200;

    private final TenantSchemaIterator tenantSchemas;
    private final TenantRepository tenantRepository;
    private final ModerationReviewService reviewService;

    public PlatformModerationQueueService(
            TenantSchemaIterator tenantSchemas,
            TenantRepository tenantRepository,
            ModerationReviewService reviewService) {
        this.tenantSchemas = tenantSchemas;
        this.tenantRepository = tenantRepository;
        this.reviewService = reviewService;
    }

    /**
     * All matching items across every active tenant, newest first, each tagged with the tenant
     * schema it came from - the token the decision endpoints below require, because a review-item
     * UUID alone is not resolvable without knowing which schema holds it. One tenant failing (bad
     * schema, one corrupt row) is logged and skipped by the iterator rather than failing the whole
     * admin page - for a read fan-out, partial visibility beats none.
     */
    public List<TenantReviewItem> list(ModerationReviewStatus status) {
        List<TenantReviewItem> out = new ArrayList<>();
        tenantSchemas.forEachActiveTenantSchema(
                schema -> {
                    List<ModerationReviewItem> items = reviewService.list(status);
                    int cap = Math.min(items.size(), MAX_ITEMS_PER_TENANT);
                    for (ModerationReviewItem item : items.subList(0, cap)) {
                        out.add(new TenantReviewItem(schema, item));
                    }
                });
        // Each tenant's slice arrives newest-first; re-sort the merged list so
        // the admin sees one coherent timeline, not tenant-sized chunks.
        out.sort(
                Comparator.comparing(
                                (TenantReviewItem t) -> t.item().getCreatedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed());
        return out;
    }

    /** Summed fan-out - there is no cross-schema table to COUNT against. */
    public long pendingCount() {
        AtomicLong count = new AtomicLong();
        tenantSchemas.forEachActiveTenantSchema(
                schema ->
                        count.addAndGet(reviewService.list(ModerationReviewStatus.PENDING).size()));
        return count.get();
    }

    public ModerationReviewItem approve(String tenantId, UUID id, String reviewedBy, String notes) {
        return inTenant(tenantId, id, () -> reviewService.approve(id, reviewedBy, notes));
    }

    public ModerationReviewItem block(String tenantId, UUID id, String reviewedBy, String notes) {
        return inTenant(tenantId, id, () -> reviewService.block(id, reviewedBy, notes));
    }

    public ModerationReviewItem escalate(
            String tenantId, UUID id, String reviewedBy, String notes) {
        return inTenant(tenantId, id, () -> reviewService.escalate(id, reviewedBy, notes));
    }

    /**
     * Runs one action inside one tenant's schema context. NOT via {@link TenantSchemaIterator} for
     * two reasons: the iterator swallows a tenant's exception (right for a batch read, wrong for a
     * decision - the 404/409 from the review service must reach the controller's handlers), and it
     * visits every tenant when exactly one is wanted.
     *
     * <p>The tenant id is validated against the ACTIVE tenant list BEFORE any context switch: the
     * value arrives from an HTTP request, and the schema name a {@link TenantContext} carries is
     * interpolated into Postgres {@code search_path} by the schema resolver - an unvalidated
     * pass-through would let a platform admin's typo (or a forged id) route queries at an arbitrary
     * schema. An unknown/inactive tenant reads as item-not-found: a 404 on the ITEM id,
     * deliberately indistinguishable from a wrong item id, so the response leaks nothing about
     * which tenant schemas exist.
     */
    private <T> T inTenant(String tenantId, UUID itemId, Supplier<T> action) {
        String schema =
                tenantRepository.findByStatus(TenantStatus.ACTIVE).stream()
                        .map(Tenant::getSchemaName)
                        .filter(s -> s.equals(tenantId))
                        .findFirst()
                        .orElseThrow(() -> new ModerationReviewNotFoundException(itemId));
        try {
            TenantContext.set(TenantContext.of(schema, "platform-admin", Set.of()));
            return action.get();
        } finally {
            // Always clear - a thread-pool-reused request thread must never
            // leak one tenant's context into an unrelated later request.
            TenantContext.clear();
        }
    }

    /**
     * One queue item plus the schema it lives in. {@code tenantSchema} - not the row's own {@code
     * tenant_id} column - is what the decision endpoint must be given back: it is the routing token
     * this service fanned out on, whereas the column is audit metadata written by the enqueue path
     * (they match in practice, but only one of them is authoritative for routing).
     */
    public record TenantReviewItem(String tenantSchema, ModerationReviewItem item) {}
}
