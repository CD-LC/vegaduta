package com.agenticai.core.sharing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/shared-with-me} - the current caller's active grants
 * across both resource types. Kept as its own small controller rather than
 * folded into {@link ShareController}: that class's {@code @RequestMapping}
 * is resource-scoped ({@code /api/{resourceKind}/{resourceId}/grants}),
 * which this endpoint isn't - it deliberately has no resource id in its
 * path.
 */
@RestController
public class SharedWithMeController {

    private final ResourceAccessService accessService;
    private final ShareRateLimiter rateLimiter;

    public SharedWithMeController(ResourceAccessService accessService, ShareRateLimiter rateLimiter) {
        this.accessService = accessService;
        this.rateLimiter = rateLimiter;
    }

    public record SharedResourceResponse(
        UUID grantId, ResourceType resourceType, UUID resourceId, PermissionLevel permissionLevel,
        String grantedBy, Instant grantedAt
    ) {
    }

    /**
     * No {@code limit}/{@code offset}: every active grant, exactly as before. With either, the
     * list is windowed (limit 1..500, newest grant first) and the response carries
     * {@code X-Total-Count} / {@code X-Has-More}. The caller's grants are one bounded
     * per-user query, so the window is applied to that list rather than by a second query.
     */
    @GetMapping("/api/shared-with-me")
    public ResponseEntity<List<SharedResourceResponse>> sharedWithMe(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        rateLimiter.assertGrantReadAllowed();
        List<SharedResourceResponse> all = accessService.listSharedWithMe().stream()
            .map(g -> new SharedResourceResponse(
                g.getId(), g.getResourceType(), g.getResourceId(), g.getPermissionLevel(), g.getGrantedBy(), g.getGrantedAt()
            ))
            .toList();
        long total = all.size();
        long skip = Math.min(Math.max(offset == null ? 0L : offset, 0L), total);
        long end = limit == null ? total : Math.min(total, skip + Math.min(Math.max(limit, 1), MAX_LIMIT));
        List<SharedResourceResponse> page = all.subList((int) skip, (int) end);
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .header("X-Has-More", String.valueOf(end < total))
            .body(page);
    }

    private static final int MAX_LIMIT = 500;
}
