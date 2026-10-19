package com.agenticai.core.audit;

import com.agenticai.core.audit.dto.AuditLogCountsResponse;
import com.agenticai.core.audit.dto.AuditLogEntryResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the audit log. No delete/update endpoint exists here on
 * purpose - see {@link AuditLogEntry}'s append-only javadoc.
 */
@RestController
public class AuditLogController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 1000;

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    // "consumer should be able to segregate the events from filters" - from/to
    // are ISO-8601 instants (e.g. 2026-07-01T00:00:00Z), both optional and
    // combinable with actionType/targetType.
    @GetMapping("/api/audit-log")
    public ResponseEntity<List<AuditLogEntryResponse>> list(
        @RequestParam(required = false) String actionType,
        @RequestParam(required = false) String targetType,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset,
        @RequestParam(required = false) String actor,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String cursor
    ) {
        if (cursor != null) {
            return keysetPage(actionType, targetType, from, to, limit, actor, q, KeysetCursor.decode(cursor));
        }
        // Neither param: up to 200 newest rows, exactly as before paging existed. Either param
        // switches to the paged contract (limit 1..1000, default 50). See LIST-ENDPOINT-PAGING.md.
        // A cursor (from X-Next-Cursor) switches to keyset paging and ignores offset.
        int size = (limit == null && offset == null)
            ? AuditLogService.LEGACY_LIST_CAP
            : AuditPaging.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        long skip = AuditPaging.clampOffset(offset);
        Page<AuditLogEntry> page = auditLogService.search(actionType, targetType, actor, q, from, to, skip, size);
        List<AuditLogEntryResponse> rows = page.getContent().stream().map(AuditLogEntryResponse::from).toList();
        ResponseEntity<List<AuditLogEntryResponse>> response =
            AuditPaging.respondWithTotal(rows, skip, page.getTotalElements());
        String next = limit != null && skip + rows.size() < page.getTotalElements() ? nextCursor(rows) : null;
        if (next == null) {
            return response;
        }
        // Hand paged clients a cursor so they can switch to keyset paging from page one.
        return ResponseEntity.ok()
            .headers(response.getHeaders())
            .header(KeysetCursor.NEXT_CURSOR_HEADER, next)
            .body(rows);
    }

    /**
     * Keyset page after {@code cursor}: no offset and no count query, so deep pages cost the same as
     * the first. {@code X-Has-More} and {@code X-Next-Cursor} say whether and where to continue;
     * {@code X-Total-Count} is not sent in this mode.
     */
    private ResponseEntity<List<AuditLogEntryResponse>> keysetPage(
        String actionType, String targetType, Instant from, Instant to, Integer limit,
        String actor, String q, KeysetCursor after
    ) {
        int size = AuditPaging.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        List<AuditLogEntryResponse> rows =
            auditLogService.searchAfter(actionType, targetType, actor, q, from, to, after, size);
        boolean more = rows.size() > size;
        List<AuditLogEntryResponse> page = more ? rows.subList(0, size) : rows;
        String next = more ? nextCursor(page) : null;
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok().header("X-Has-More", String.valueOf(more));
        if (next != null) {
            ok.header(KeysetCursor.NEXT_CURSOR_HEADER, next);
        }
        return ok.body(page);
    }

    /** Cursor of the last row, or null when there is none to point at. */
    private static String nextCursor(List<AuditLogEntryResponse> rows) {
        AuditLogEntryResponse last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        return last == null || last.id() == null || last.createdAt() == null
            ? null
            : new KeysetCursor(last.createdAt(), last.id()).encode();
    }

    // Exact per-outcome counts of one action type over a window (default: the
    // last 7 days, at most 90 - a longer window is a 400, not a silent clamp).
    // Read-only. Same PERM_VIEW_AUDIT_LOG gate as the list above (SecurityConfig
    // matches /api/audit-log/**) and the same tenant scope. See
    // AuditLogService#counts for the filter and window rules.
    @GetMapping("/api/audit-log/counts")
    public AuditLogCountsResponse counts(
        @RequestParam String actionType,
        @RequestParam(required = false) String targetId,
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String detailContains,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        return auditLogService.counts(actionType, targetId, actorId, detailContains, from, to);
    }

    // Backs the filter dropdown - the real, always-current alphabet of
    // action types instead of a hardcoded frontend list.
    @GetMapping("/api/audit-log/action-types")
    public List<String> actionTypes() {
        return auditLogService.distinctActionTypes();
    }
}
