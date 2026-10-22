package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-tenant audit search for the platform operator -
 * {@code GET /api/platform/audit/search}, gated by the blanket
 * {@code /api/platform/** -> ROLE_platform-admin} rule. All filters optional
 * and ANDed; {@code actionType} accepts a trailing {@code *} for a prefix match
 * (e.g. {@code platform.user.*}). Platform-operator actions written by
 * {@link PlatformUserController} land in the target tenant's log, so they are
 * found here. The response reports the scan bounds - see
 * {@link PlatformAuditSearchService}.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/audit")
@Profile("security")
public class PlatformAuditSearchController {

    private final PlatformAuditSearchService service;

    public PlatformAuditSearchController(PlatformAuditSearchService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public ResponseEntity<PlatformAuditSearchService.SearchResult> search(
        @RequestParam(name = "tenantId", required = false) String tenantId,
        @RequestParam(name = "actorId", required = false) String actorId,
        @RequestParam(name = "actionType", required = false) String actionType,
        @RequestParam(name = "targetType", required = false) String targetType,
        @RequestParam(name = "outcome", required = false) String outcome,
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(name = "limit", defaultValue = "" + PlatformAuditSearchService.DEFAULT_LIMIT) int limit,
        @RequestParam(name = "offset", defaultValue = "0") int offset
    ) {
        PlatformAuditSearchService.SearchResult result = service.search(
            new PlatformAuditSearchService.Filters(tenantId, actorId, actionType, targetType, outcome, from, to),
            limit, offset);
        // X-Total-Count is a lower bound while X-Has-More is true: each tenant is only read up to offset+limit.
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(Math.max(result.knownTotal(), result.offset() + result.rows().size())))
            .header("X-Has-More", String.valueOf(result.hasMore()))
            .body(result);
    }
}
