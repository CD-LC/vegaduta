package com.agenticai.core.tenancy.aireport;

import com.agenticai.core.tenancy.ListPaging;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * the app-store compliance requirements (described inline).
 *
 * <ul>
 *   <li>{@code POST /api/ai-reports} - any signed-in user (falls through to
 *       {@code anyRequest().authenticated()} in SecurityConfig) -> 202
 *       {@code {"id","status":"OPEN"}}.</li>
 *   <li>{@code GET /api/platform/ai-reports?status=&limit=} and
 *       {@code PATCH /api/platform/ai-reports/{id}} - gated by SecurityConfig's
 *       {@code /api/platform/**} -> {@code ROLE_platform-admin} rule, and again
 *       in {@link AiContentReportService}.</li>
 * </ul>
 */
@RestController
public class AiContentReportController {

    private final AiContentReportService service;

    public AiContentReportController(AiContentReportService service) {
        this.service = service;
    }

    @PostMapping("/api/ai-reports")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody AiContentReportRequest request) {
        AiContentReport saved = service.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(Map.of("id", saved.getId(), "status", saved.getStatus()));
    }

    @GetMapping("/api/platform/ai-reports")
    public ResponseEntity<Map<String, Object>> list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        long skip = ListPaging.offset(offset);
        List<AiContentReport> rows = service.list(status, limit, skip);
        List<Map<String, Object>> reports = rows.stream().map(AiContentReportService::toView).toList();
        int size = limit == null
            ? AiContentReportService.DEFAULT_LIMIT
            : Math.max(1, Math.min(AiContentReportService.MAX_LIMIT, limit));
        // Reuse ListPaging's header logic (count only runs when the page could hide rows).
        var headers = ListPaging.respond(rows, size, skip, () -> service.count(status)).getHeaders();
        return ResponseEntity.ok().headers(headers).body(Map.of("reports", reports));
    }

    @PatchMapping("/api/platform/ai-reports/{id}")
    public Map<String, Object> review(@PathVariable UUID id, @RequestBody AiContentReportReviewRequest request) {
        return AiContentReportService.toView(service.review(id, request));
    }
}
