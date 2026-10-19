package com.agenticai.core.platform;

import com.agenticai.core.audit.AuditPaging;
import java.util.List;
import org.springframework.http.ResponseEntity;

/**
 * {@code ?limit=&offset=} windowing for the platform lists that are computed in memory by a capped
 * cross-tenant fan-out (issues, activity, abuse scores/anomalies/throttles). The service already
 * returns the whole bounded list, so the window is cut here and {@code X-Total-Count} is that list's
 * size. Neither param present: the full list, exactly as before (headers are additive). Either
 * present: limit 1..{@value #MAX_LIMIT}, default {@value #DEFAULT_LIMIT}. See
 * {@code docs/api/LIST-ENDPOINT-PAGING.md}.
 */
public final class PlatformListPaging {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    private PlatformListPaging() {}

    public static <T> ResponseEntity<List<T>> window(List<T> all, Integer limit, Long offset) {
        if (limit == null && offset == null) {
            return AuditPaging.respondWithTotal(all, 0, all.size());
        }
        int size = AuditPaging.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        long skip = AuditPaging.clampOffset(offset);
        int from = (int) Math.min(skip, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        return AuditPaging.respondWithTotal(List.copyOf(all.subList(from, to)), from, all.size());
    }
}
