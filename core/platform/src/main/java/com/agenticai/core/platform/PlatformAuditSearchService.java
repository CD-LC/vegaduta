package com.agenticai.core.platform;

import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditLogRepository;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * Read-only fan-out search over every tenant's {@code audit_log_entry} table.
 *
 * <p><b>Schema resolution.</b> {@code audit_log_entry} is a per-tenant table
 * (not {@code control_plane}-qualified), and the operator's token is
 * tenant-less, so a bare repository call would hit the {@code public} schema
 * where the table does not exist. Hibernate's {@code TenantSchemaResolver}
 * takes the schema from {@link TenantContext#getTenantId()}, so each tenant is
 * queried with a {@link TenantContext} carrying that tenant's schema - the same
 * mechanism {@code PlatformTenantMetricsService} and {@code TenantSchemaIterator}
 * use. The schema names come exclusively from {@link Tenant#getSchemaName()}
 * rows read out of the control plane; a request-supplied {@code tenantId} is
 * only ever compared against that list, never passed through, so a forged value
 * cannot route a query at an arbitrary schema.
 *
 * <p><b>Bounds.</b> At most {@link #MAX_TENANTS} tenants are scanned and at
 * most {@code limit} (max {@link #MAX_LIMIT}) rows returned; each tenant query
 * is itself capped at {@code limit}. The response states what was scanned and
 * whether anything was cut off, so a truncated result is never presented as
 * complete. Scope: ACTIVE, non-demo tenants only (erased tenants' schemas are
 * gone by design; demo sandboxes are excluded like every other admin roster).
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class PlatformAuditSearchService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuditSearchService.class);
    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;
    static final int MAX_TENANTS = 200;
    /** Largest {@code offset} honoured; each tenant is queried for {@code offset + limit} rows, so it is bounded. */
    static final int MAX_OFFSET = 2000;
    private static final int DETAIL_MAX = 500;

    private final TenantRepository tenantRepository;
    private final AuditLogRepository auditLogRepository;

    public PlatformAuditSearchService(TenantRepository tenantRepository, AuditLogRepository auditLogRepository) {
        this.tenantRepository = tenantRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public record Filters(String tenantId, String actorId, String actionType, String targetType,
                          String outcome, Instant from, Instant to) {
    }

    public record AuditRow(String tenantId, String tenantName, String id, String actorId, String actionType,
                           String targetType, String targetId, String outcome, String detail, Instant createdAt) {
    }

    /**
     * @param truncated      true when more matching rows may exist than were returned
     *                       (a per-tenant or overall row cap was hit, or tenants beyond
     *                       the tenant cap were not scanned)
     * @param tenantsScanned tenants actually queried
     * @param tenantsInScope tenants eligible for this query before the tenant cap
     * @param tenantsFailed  tenants whose query threw (skipped, logged)
     * @param offset         rows skipped from the newest-first merged result (0 for the first page)
     * @param hasMore        true when rows beyond this page may exist (same condition as {@code truncated})
     * @param knownTotal     matching rows seen while merging (offset + page + the rows after it that were
     *                       fetched); a lower bound whenever {@code hasMore} is true
     */
    public record SearchResult(List<AuditRow> rows, boolean truncated, int tenantsScanned, int tenantsInScope,
                               int tenantsFailed, int rowLimit, int maxTenants, int offset, boolean hasMore,
                               int knownTotal) {
    }

    public SearchResult search(Filters f, int requestedLimit) {
        return search(f, requestedLimit, 0);
    }

    /** Page {@code offset}..{@code offset+limit} of the newest-first merged result (offset clamped to 0..{@link #MAX_OFFSET}). */
    public SearchResult search(Filters f, int requestedLimit, int requestedOffset) {
        int offset = Math.min(Math.max(requestedOffset, 0), MAX_OFFSET);
        int limit = requestedLimit <= 0 ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        AuditOutcome outcome = parseOutcome(f.outcome());
        Specification<AuditLogEntry> spec = spec(f, outcome);

        String tenantFilter = blankToNull(f.tenantId());
        List<Tenant> inScope = tenantRepository.findActiveExcludingDemo().stream()
            .filter(t -> tenantFilter == null || tenantFilter.equals(t.getSchemaName())
                || (t.getId() != null && tenantFilter.equals(t.getId().toString())))
            .sorted(Comparator.comparing(t -> String.valueOf(t.getSchemaName())))
            .toList();
        List<Tenant> toScan = inScope.size() > MAX_TENANTS ? inScope.subList(0, MAX_TENANTS) : inScope;
        boolean truncated = inScope.size() > MAX_TENANTS;

        List<AuditRow> all = new ArrayList<>();
        int failed = 0;
        Optional<TenantContext> previous = TenantContext.current();
        try {
            for (Tenant tenant : toScan) {
                String schema = tenant.getSchemaName();
                try {
                    TenantContext.set(TenantContext.of(schema, "platform-admin", Set.of()));
                    List<AuditLogEntry> page = auditLogRepository
                        .findAll(spec, PageRequest.of(0, offset + limit)).getContent();
                    if (page.size() >= offset + limit) {
                        truncated = true;
                    }
                    for (AuditLogEntry e : page) {
                        all.add(toRow(schema, tenant.getName(), e));
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Audit search failed for tenant schema '{}' (skipped)", schema, e);
                }
            }
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }

        all.sort(Comparator.comparing(AuditRow::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        int knownTotal = all.size();
        if (all.size() > offset + limit) {
            truncated = true;
        }
        int from = Math.min(offset, all.size());
        int to = Math.min(offset + limit, all.size());
        all = new ArrayList<>(all.subList(from, to));
        return new SearchResult(all, truncated, toScan.size(), inScope.size(), failed, limit, MAX_TENANTS,
            offset, truncated, knownTotal);
    }

    private static Specification<AuditLogEntry> spec(Filters f, AuditOutcome outcome) {
        String actor = blankToNull(f.actorId());
        String action = blankToNull(f.actionType());
        String target = blankToNull(f.targetType());
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (actor != null) {
                p.add(cb.equal(root.get("actorId"), actor));
            }
            if (action != null) {
                if (action.endsWith("*")) {
                    p.add(cb.like(root.<String>get("actionType"),
                        escapeLike(action.substring(0, action.length() - 1)) + "%", '\\'));
                } else {
                    p.add(cb.equal(root.get("actionType"), action));
                }
            }
            if (target != null) {
                p.add(cb.equal(root.get("targetType"), target));
            }
            if (outcome != null) {
                p.add(cb.equal(root.get("outcome"), outcome));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.<Instant>get("createdAt"), f.to()));
            }
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static AuditRow toRow(String schema, String tenantName, AuditLogEntry e) {
        String detail = e.getDetail();
        if (detail != null && detail.length() > DETAIL_MAX) {
            detail = detail.substring(0, DETAIL_MAX) + "...";
        }
        return new AuditRow(schema, tenantName, String.valueOf(e.getId()), e.getActorId(), e.getActionType(),
            e.getTargetType(), e.getTargetId(), e.getOutcome() == null ? null : e.getOutcome().name(), detail,
            e.getCreatedAt());
    }

    private static AuditOutcome parseOutcome(String raw) {
        String v = blankToNull(raw);
        if (v == null) {
            return null;
        }
        try {
            return AuditOutcome.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown outcome '" + v + "'. Expected one of "
                + Arrays.toString(AuditOutcome.values()) + ".");
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
