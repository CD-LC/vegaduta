package com.agenticai.core.audit;

import com.agenticai.core.audit.dto.AuditLogCountsResponse;
import com.agenticai.core.audit.dto.AuditLogEntryResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * Records audit entries. Deliberately the only way anything in this codebase
 * writes to {@code audit_log_entry} - no other class should touch
 * {@link AuditLogRepository} directly, so "every state-changing action is
 * audited" stays a property of going through one call, not a convention
 * every future controller has to remember on its own.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public AuditLogEntry record(
        String actorId,
        String actionType,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        String detail
    ) {
        AuditLogEntry entry = new AuditLogEntry(actorId, actionType, targetType, targetId, outcome, detail);
        return repository.save(entry);
    }

    public List<AuditLogEntry> list(String actionType, String targetType) {
        return list(actionType, targetType, null, null);
    }

    // "consumer should be able to segregate the events from filters" - from/to
    // extend the existing actionType/targetType filters with a date range,
    // all optional and combinable. Kept as a distinct overload (not a
    // breaking signature change) so #list(actionType, targetType) above -
    // and everything already calling it - keeps working unchanged.
    public List<AuditLogEntry> list(String actionType, String targetType, Instant from, Instant to) {
        return search(actionType, targetType, from, to, 0, LEGACY_LIST_CAP).getContent();
    }

    /** Rows returned when a caller sends neither {@code limit} nor {@code offset} (the pre-paging behaviour). */
    public static final int LEGACY_LIST_CAP = 200;

    /**
     * One page of the audit log, newest first (id as tiebreaker so consecutive offset pages never
     * duplicate or skip equal-timestamp rows). The returned {@link Page} carries the real total of
     * matching rows, which the controller reports as {@code X-Total-Count}.
     */
    public Page<AuditLogEntry> search(
        String actionType, String targetType, Instant from, Instant to, long offset, int limit
    ) {
        return search(actionType, targetType, null, null, from, to, offset, limit);
    }

    /** Longest free-text {@code q} accepted; a longer one is a 400 (it is matched as an unindexed substring). */
    public static final int MAX_QUERY_CHARS = 100;

    /**
     * {@link #search(String, String, Instant, Instant, long, int)} plus two server-side filters:
     * {@code actor} (exact match on {@code actorId}, which is indexed) and {@code q} (case-insensitive,
     * literal substring over action type, actor, target id and detail; LIKE wildcards are escaped).
     * Blank values are ignored. All filters AND together with the existing ones.
     */
    public Page<AuditLogEntry> search(
        String actionType, String targetType, String actor, String q,
        Instant from, Instant to, long offset, int limit
    ) {
        return repository.findAll(
            filters(actionType, targetType, actor, q, from, to, null),
            AuditPaging.offsetLimit(offset, limit, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    /**
     * Keyset page: the same filters, rows strictly after {@code after} (null = from the newest), at
     * most {@code limit + 1} rows so the caller can tell whether another page exists without a count.
     */
    public List<AuditLogEntryResponse> searchAfter(
        String actionType, String targetType, String actor, String q,
        Instant from, Instant to, KeysetCursor after, int limit
    ) {
        return repository.findAll(
            filters(actionType, targetType, actor, q, from, to, after),
            AuditPaging.offsetLimit(0, limit + 1, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
            .getContent().stream().map(AuditLogEntryResponse::from).toList();
    }

    private static Specification<AuditLogEntry> filters(
        String actionType, String targetType, String actor, String q,
        Instant from, Instant to, KeysetCursor after
    ) {
        String normalizedActor = blankToNull(actor);
        String normalizedQ = blankToNull(q);
        if (normalizedQ != null && normalizedQ.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("q must be at most " + MAX_QUERY_CHARS + " characters.");
        }
        String normalizedActionType = actionType != null && !actionType.isBlank() ? actionType : null;
        String normalizedTargetType = targetType != null && !targetType.isBlank() ? targetType : null;

        // Built as a Specification (not a static "(:param IS NULL OR ...)"
        // @Query) specifically so an unset filter never becomes a bind
        // parameter at all - see AuditLogRepository's javadoc for the
        // Postgres/Hibernate parameter-type-inference bug that pattern hit
        // for the date-range filters.
        Specification<AuditLogEntry> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (normalizedActionType != null) {
                predicates.add(cb.equal(root.get("actionType"), normalizedActionType));
            }
            if (normalizedTargetType != null) {
                predicates.add(cb.equal(root.get("targetType"), normalizedTargetType));
            }
            if (normalizedActor != null) {
                predicates.add(cb.equal(root.get("actorId"), normalizedActor));
            }
            if (normalizedQ != null) {
                String pattern = "%" + escapeLike(normalizedQ.toLowerCase(java.util.Locale.ROOT)) + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.<String>get("actionType")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.<String>get("actorId")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.<String>get("targetId")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.<String>get("detail")), pattern, LIKE_ESCAPE)));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (after != null) {
                // Keyset seek: strictly older than the cursor row in (createdAt DESC, id DESC).
                predicates.add(cb.or(
                    cb.lessThan(root.<Instant>get("createdAt"), after.timestamp()),
                    cb.and(
                        cb.equal(root.get("createdAt"), after.timestamp()),
                        cb.lessThan(root.<java.util.UUID>get("id"), after.id()))));
            }
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return spec;
    }

    /** Longest window {@link #counts} will count over. A longer request is refused, never silently shortened. */
    public static final Duration MAX_COUNT_WINDOW = Duration.ofDays(90);
    /** Window used when the caller gives no {@code from}. */
    public static final Duration DEFAULT_COUNT_WINDOW = Duration.ofDays(7);
    static final int MAX_DETAIL_FILTER_CHARS = 64;

    /**
     * How many entries of one action type were recorded in a window, per {@link AuditOutcome}. Read-only, and
     * exact: these are COUNT queries over the whole window, not a tally of the 200-row page {@link #list}
     * returns, so a busy tenant's numbers are not truncated.
     *
     * <p>Tenant scope is the same as every other read here: the repository runs against the calling tenant's
     * schema, and there is no parameter through which another tenant could be named.
     *
     * <p>Window: {@code from} inclusive, {@code to} exclusive. A missing {@code to} is "now"; a missing
     * {@code from} is {@link #DEFAULT_COUNT_WINDOW} before {@code to}. The resolved bounds are echoed in the
     * response. An empty or inverted window, or one longer than {@link #MAX_COUNT_WINDOW}, is a 400
     * ({@link IllegalArgumentException}).
     *
     * <p>Optional filters are exact matches on {@code targetId} and {@code actorId}, and a literal,
     * case-sensitive substring of {@code detail} ({@code detailContains}; LIKE wildcards in it are escaped).
     * The substring filter exists because some writers put the distinction a reader needs in the detail text:
     * a hook that redacted and a hook that only logged both record {@link AuditOutcome#SUCCESS}.
     *
     * <p>One COUNT per outcome (six cheap queries) rather than one GROUP BY, so the filters are built by the
     * same Specification approach as {@link #list} and an unset filter never becomes a bind parameter.
     */
    public AuditLogCountsResponse counts(
        String actionType, String targetId, String actorId, String detailContains, Instant from, Instant to
    ) {
        return counts(actionType, targetId, actorId, detailContains, from, to, Instant.now());
    }

    AuditLogCountsResponse counts(
        String actionType, String targetId, String actorId, String detailContains, Instant from, Instant to, Instant now
    ) {
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType is required.");
        }
        String action = actionType.strip();
        String target = blankToNull(targetId);
        String actor = blankToNull(actorId);
        String detail = blankToNull(detailContains);
        if (detail != null && detail.length() > MAX_DETAIL_FILTER_CHARS) {
            throw new IllegalArgumentException("detailContains must be at most " + MAX_DETAIL_FILTER_CHARS + " characters.");
        }
        Instant end = to != null ? to : now;
        Instant start = from != null ? from : end.minus(DEFAULT_COUNT_WINDOW);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must be before to.");
        }
        if (Duration.between(start, end).compareTo(MAX_COUNT_WINDOW) > 0) {
            throw new IllegalArgumentException("The window from..to must be at most " + MAX_COUNT_WINDOW.toDays() + " days.");
        }

        Map<AuditOutcome, Long> byOutcome = new EnumMap<>(AuditOutcome.class);
        long total = 0;
        for (AuditOutcome outcome : AuditOutcome.values()) {
            long n = repository.count(countSpec(action, target, actor, detail, start, end, outcome));
            byOutcome.put(outcome, n);
            total += n;
        }
        return new AuditLogCountsResponse(action, target, actor, detail, start, end, total, byOutcome);
    }

    private static Specification<AuditLogEntry> countSpec(
        String action, String target, String actor, String detail, Instant start, Instant end, AuditOutcome outcome
    ) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("actionType"), action));
            predicates.add(cb.equal(root.get("outcome"), outcome));
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            predicates.add(cb.lessThan(root.get("createdAt"), end));
            if (target != null) {
                predicates.add(cb.equal(root.get("targetId"), target));
            }
            if (actor != null) {
                predicates.add(cb.equal(root.get("actorId"), actor));
            }
            if (detail != null) {
                predicates.add(cb.like(root.get("detail"), "%" + escapeLike(detail) + "%", LIKE_ESCAPE));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private static final char LIKE_ESCAPE = '!';

    /** Makes {@code %}, {@code _} and the escape character itself literal inside a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    // Backs the event-type filter dropdown (AuditLogPanel.js) - every
    // actionType ever actually recorded, so the filter always reflects
    // reality instead of a hand-maintained frontend list.
    public List<String> distinctActionTypes() {
        return repository.findDistinctActionTypes();
    }
}
